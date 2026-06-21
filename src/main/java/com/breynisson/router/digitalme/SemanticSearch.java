package com.breynisson.router.digitalme;

import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import com.breynisson.router.mcp.SummarizeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Component
public class SemanticSearch {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearch.class);
    private static final int SNIPPET_CHARS = 2_000;

    private final EmbeddingIndex embeddingIndex;
    private final SummarizeClient summarizeClient;
    private final Path mcpResourcesDir;

    public SemanticSearch(
            EmbeddingIndex embeddingIndex,
            SummarizeClient summarizeClient,
            @Value("${data.dir:.}") String dataDir) {
        this.embeddingIndex = embeddingIndex;
        this.summarizeClient = summarizeClient;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
    }

    /** Returns top-10 semantically similar results; empty list if Ollama is unavailable. */
    public List<SearchResult> search(String query) {
        return embeddingIndex.findSimilar(query, 50).stream()
                .filter(r -> !ExclusionRules.isExcluded(r.sourceUrl()))
                .map(r -> {
                    Path p = Path.of(r.filePath());
                    String snip = "";
                    try {
                        snip = snippet(Files.readString(p, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        log.warn("Could not read {} for snippet", r.filePath());
                    }
                    return new SearchResult(r.sourceUrl(), p.getFileName().toString(), snip, (double) r.score());
                })
                .toList();
    }

    /** Summarizes the given text; returns null if Ollama is unavailable. */
    public String summarize(String text) {
        return summarizeClient.summarize(text);
    }

    /** Extracts content after the first line (source URL), normalised and capped at SNIPPET_CHARS. */
    public static String snippet(String raw) {
        int nl = raw.indexOf('\n');
        String body = nl >= 0 ? raw.substring(nl + 1) : "";
        boolean truncated = body.length() > SNIPPET_CHARS;
        if (truncated) body = body.substring(0, SNIPPET_CHARS);
        String result = body.replace("\\n", " ").replace("\\t", " ").replace("\\r", " ")
                            .replaceAll("\\s+", " ").strip();
        return truncated ? result + " <truncated, use fetch tool>" : result;
    }
}
