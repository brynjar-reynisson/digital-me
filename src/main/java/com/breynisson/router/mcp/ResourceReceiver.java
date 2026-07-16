package com.breynisson.router.mcp;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;
import java.util.regex.Pattern;

public class ResourceReceiver {

    private static final Logger log = LoggerFactory.getLogger(ResourceReceiver.class);
    public static final String MCP_RESOURCES_DIR = "mcp-resources";
    private static final Pattern INVALID_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    private final Path mcpResourcesDir;
    private YearMonth cachedMonth;
    private Path cachedMonthDir;

    public ResourceReceiver(String dataDir) {
        this.mcpResourcesDir = Paths.get(dataDir, MCP_RESOURCES_DIR);
    }

    public Path addContent(AddContentRequest request) throws IOException {
        String rawName = request.getName() != null ? request.getName() : request.getSource();
        // prefix with day-hour-minute-second to avoid conflicts; sanitize invalid chars
        LocalDateTime now = LocalDateTime.now();
        String fileName = timestampPrefix(now) + "-" + rawName;
        fileName = INVALID_CHARS.matcher(fileName).replaceAll("_");
        if (!fileName.toLowerCase().endsWith(".txt")) {
            fileName += ".txt";
        }
        Path monthDir = monthDir(YearMonth.from(now));
        Path written = monthDir.resolve(fileName);
        Files.writeString(written, request.getSource() + "\n" + request.getContent());
        log.info("Wrote resource: {}/{}", monthDir.getFileName(), fileName);
        return written;
    }

    /**
     * Deletes any previously indexed mcp-resources files (and their embedding rows) for this exact
     * source URL. Looked up via the embedding table rather than a filesystem walk, since mcp-resources
     * can hold tens of thousands of files and this runs on every addContent call.
     */
    public void deleteExistingFor(String sourceUrl) {
        Set<String> filePaths = McpEmbeddingDao.findFilePathsBySourceUrl(sourceUrl);
        McpEmbeddingDao.deleteBySourceUrl(sourceUrl);
        for (String filePath : filePaths) {
            try {
                Files.deleteIfExists(Paths.get(filePath));
            } catch (IOException e) {
                log.warn("Error deleting stale resource file {}", filePath, e);
            }
        }
    }

    /** Day-hour-minute-second prefix shared by all mcp-resources filename writers, to keep the convention consistent. */
    public static String timestampPrefix(LocalDateTime time) {
        return String.format("%02d-%02d-%02d-%02d",
                time.getDayOfMonth(),
                time.getHour(),
                time.getMinute(),
                time.getSecond());
    }

    /** Extracts the source URL from an mcp-resources file (first line, trimmed). */
    public static String firstLine(String content) {
        int nl = content.indexOf('\n');
        return (nl >= 0 ? content.substring(0, nl) : content).trim();
    }

    private Path monthDir(YearMonth yearMonth) throws IOException {
        if (!yearMonth.equals(cachedMonth)) {
            cachedMonth = yearMonth;
            cachedMonthDir = mcpResourcesDir.resolve(yearMonth.toString());
            Files.createDirectories(cachedMonthDir);
        }
        return cachedMonthDir;
    }
}
