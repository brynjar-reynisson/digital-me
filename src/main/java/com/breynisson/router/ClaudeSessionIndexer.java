package com.breynisson.router;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.TextEntryMetadataDao;
import com.breynisson.router.jdbc.model.TextEntry;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ClaudeSessionIndexer {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSessionIndexer.class);
    private static final Path DEFAULT_CLAUDE_PROJECTS = Path.of(System.getProperty("user.home"), ".claude", "projects");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT_HASH_KEY = "content-hash";
    /** A session file still being actively written gets its mtime bumped on nearly every turn;
     * waiting for it to go quiet avoids re-embedding the whole (possibly multi-MB) transcript every scheduler tick. */
    private static final long QUIET_PERIOD_MILLIS = 180_000;

    private final EmbeddingIndex embeddingIndex;
    private final Path mcpResourcesDir;
    private final Path claudeProjectsDir;
    private final boolean migrating;

    @Autowired
    public ClaudeSessionIndexer(EmbeddingIndex embeddingIndex, @Value("${data.dir:.}") String dataDir,
            @Value("${claude.projects.dir:}") String claudeProjectsDir,
            @Value("${digitalme.migrate-sqlite-path:}") String migrateSqlitePath) {
        this.embeddingIndex = embeddingIndex;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
        this.claudeProjectsDir = claudeProjectsDir.isBlank() ? DEFAULT_CLAUDE_PROJECTS : Path.of(claudeProjectsDir);
        this.migrating = !migrateSqlitePath.isBlank();
    }

    /** Convenience constructor for tests: not in migration mode. */
    public ClaudeSessionIndexer(EmbeddingIndex embeddingIndex, String dataDir, String claudeProjectsDir) {
        this(embeddingIndex, dataDir, claudeProjectsDir, "");
    }

    // Skipped during the one-time SQLite-to-Postgres migration run: indexing new sessions here
    // would race the migrator's own bulk copy into the same MCP_EMBEDDING table.
    @Scheduled(fixedDelay = 60_000)
    public void indexAll() {
        if (migrating || !Files.isDirectory(claudeProjectsDir)) return;
        try (Stream<Path> walk = Files.walk(claudeProjectsDir, 2)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".jsonl"))
                .forEach(this::indexSession);
        } catch (IOException e) {
            log.warn("Error scanning Claude projects", e);
        }
    }

    private void indexSession(Path jsonlFile) {
        try {
            String projectName = jsonlFile.getParent().getFileName().toString();
            String sessionUuid = jsonlFile.getFileName().toString().replace(".jsonl", "");
            String sourceUrl = "claude://" + projectName + "/" + sessionUuid;

            long fileModified = jsonlFile.toFile().lastModified();
            if (System.currentTimeMillis() - fileModified < QUIET_PERIOD_MILLIS) {
                return; // still being actively written; wait until the session goes quiet
            }

            List<TextEntry> existing = TextEntryDao.findByName(sourceUrl);
            if (!existing.isEmpty() && existing.get(0).instant.getEpochSecond() >= fileModified / 1000) {
                return;
            }

            ParsedSession parsed = parseJsonl(jsonlFile);
            if (parsed.content().isBlank()) return;

            String contentHash = sha256Hex(parsed.content());
            String existingUuid = existing.isEmpty() ? null : existing.get(0).uuid;
            if (existingUuid != null && contentHash.equals(TextEntryMetadataDao.get(existingUuid, CONTENT_HASH_KEY))) {
                // Only filtered-out events (e.g. sidechain turns) changed since last index — content is identical,
                // so bump TIME to the new mtime and skip the re-embed instead of redoing the same work.
                TextEntryDao.update(new TextEntry(existingUuid, Instant.ofEpochMilli(fileModified), sourceUrl));
                return;
            }

            LocalDateTime sessionStart = parsed.startTime() != null
                    ? parsed.startTime()
                    : LocalDateTime.ofInstant(Instant.ofEpochMilli(fileModified), ZoneId.systemDefault());

            deleteOldResourceFiles(sourceUrl);
            Path resourceFile = writeResourceFile(sourceUrl, projectName, parsed.content(), sessionStart);
            if (resourceFile == null) return;

            String uuid = existingUuid != null
                    ? existingUuid
                    : TextEntryDao.insert(sourceUrl, Instant.ofEpochMilli(fileModified));
            if (existingUuid != null) {
                TextEntryDao.update(new TextEntry(existingUuid, Instant.ofEpochMilli(fileModified), sourceUrl));
            }
            TextEntryMetadataDao.upsert(uuid, CONTENT_HASH_KEY, contentHash);
            embeddingIndex.indexFile(resourceFile);
            log.info("Indexed Claude session {} ({})", sessionUuid, projectName);
        } catch (Exception e) {
            log.warn("Error indexing Claude session {}", jsonlFile, e);
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ParsedSession(String content, LocalDateTime startTime) {}

    private ParsedSession parseJsonl(Path file) throws IOException {
        List<String[]> turns = new ArrayList<>();
        Set<String> seenAssistantIds = new HashSet<>();
        LocalDateTime startTime = null;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = MAPPER.readTree(line);
                if (node.path("isSidechain").asBoolean(false)) continue;

                String type = node.path("type").asText("");
                if ("user".equals(type)) {
                    JsonNode content = node.path("message").path("content");
                    if (content.isTextual()) {
                        String text = content.asText().strip();
                        if (!text.isBlank()) {
                            if (startTime == null) {
                                String ts = node.path("timestamp").asText("");
                                if (!ts.isBlank()) {
                                    startTime = LocalDateTime.ofInstant(Instant.parse(ts), ZoneId.systemDefault());
                                }
                            }
                            turns.add(new String[]{"user", text});
                        }
                    }
                } else if ("assistant".equals(type)) {
                    String msgId = node.path("message").path("id").asText("");
                    if (msgId.isBlank() || !seenAssistantIds.add(msgId)) continue;
                    JsonNode content = node.path("message").path("content");
                    if (content.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : content) {
                            if ("text".equals(block.path("type").asText(""))) {
                                sb.append(block.path("text").asText());
                            }
                        }
                        String text = sb.toString().strip();
                        if (!text.isBlank()) turns.add(new String[]{"assistant", text});
                    }
                }
            }
        }

        if (turns.isEmpty()) return new ParsedSession("", startTime);
        StringBuilder result = new StringBuilder();
        for (String[] turn : turns) {
            result.append("user".equals(turn[0]) ? "User: " : "Claude: ");
            result.append(turn[1]).append("\n\n");
        }
        return new ParsedSession(result.toString().strip(), startTime);
    }

    void deleteOldResourceFiles(String sourceUrl) {
        SummaryCacheDao.deleteBySourceUrl(sourceUrl);
        if (!Files.isDirectory(mcpResourcesDir)) return;
        try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".txt"))
                .forEach(file -> {
                    try {
                        String raw = Files.readString(file, StandardCharsets.UTF_8);
                        if (sourceUrl.equals(ResourceReceiver.firstLine(raw))) {
                            McpEmbeddingDao.deleteByFilePath(file.toAbsolutePath().toString());
                            Files.delete(file);
                            log.debug("Deleted stale Claude resource: {}", file.getFileName());
                        }
                    } catch (IOException e) {
                        log.warn("Error during Claude resource cleanup for {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.warn("Error walking mcp-resources for cleanup", e);
        }
    }

    static String buildFileName(String projectName, LocalDateTime sessionStart) {
        return ResourceReceiver.timestampPrefix(sessionStart) + "-claudecode-" + projectName + ".txt";
    }

    private Path writeResourceFile(String sourceUrl, String projectName, String content, LocalDateTime sessionStart) {
        try {
            String yearMonth = YearMonth.from(sessionStart).toString();
            String fileName = buildFileName(projectName, sessionStart);
            Path monthDir = mcpResourcesDir.resolve(yearMonth);
            Files.createDirectories(monthDir);
            Path file = monthDir.resolve(fileName);
            Files.writeString(file, sourceUrl + "\n" + content);
            return file;
        } catch (IOException e) {
            log.warn("Error writing Claude resource file", e);
            return null;
        }
    }
}
