package com.breynisson.router;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.TextEntry;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ClaudeSessionIndexer {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSessionIndexer.class);
    private static final Path CLAUDE_PROJECTS = Path.of(System.getProperty("user.home"), ".claude", "projects");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmbeddingIndex embeddingIndex;
    private final Path mcpResourcesDir;

    public ClaudeSessionIndexer(EmbeddingIndex embeddingIndex, @Value("${data.dir:.}") String dataDir) {
        this.embeddingIndex = embeddingIndex;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
    }

    @Scheduled(fixedDelay = 60_000)
    public void indexAll() {
        if (!Files.isDirectory(CLAUDE_PROJECTS)) return;
        try (Stream<Path> walk = Files.walk(CLAUDE_PROJECTS, 2)) {
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
            List<TextEntry> existing = TextEntryDao.findByName(sourceUrl);
            if (!existing.isEmpty() && existing.get(0).instant.getEpochSecond() >= fileModified / 1000) {
                return;
            }

            String content = parseJsonl(jsonlFile);
            if (content.isBlank()) return;

            deleteOldResourceFiles(sourceUrl);
            Path resourceFile = writeResourceFile(sourceUrl, projectName, content);
            if (resourceFile == null) return;

            if (existing.isEmpty()) {
                TextEntryDao.insert(sourceUrl, Instant.ofEpochMilli(fileModified));
            } else {
                TextEntry e = existing.get(0);
                TextEntryDao.update(new TextEntry(e.uuid, Instant.ofEpochMilli(fileModified), e.name));
            }
            embeddingIndex.indexFile(resourceFile);
            log.info("Indexed Claude session {} ({})", sessionUuid, projectName);
        } catch (Exception e) {
            log.warn("Error indexing Claude session {}", jsonlFile, e);
        }
    }

    private String parseJsonl(Path file) throws IOException {
        List<String[]> turns = new ArrayList<>();
        Set<String> seenAssistantIds = new HashSet<>();

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
                        if (!text.isBlank()) turns.add(new String[]{"user", text});
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

        if (turns.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String[] turn : turns) {
            result.append("user".equals(turn[0]) ? "User: " : "Claude: ");
            result.append(turn[1]).append("\n\n");
        }
        return result.toString().strip();
    }

    private void deleteOldResourceFiles(String sourceUrl) {
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

    private Path writeResourceFile(String sourceUrl, String projectName, String content) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String yearMonth = YearMonth.from(now).toString();
            String timestamp = now.format(TIMESTAMP_FMT);
            String fileName = yearMonth + "-claudecode-" + projectName + "-" + timestamp + ".txt";
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
