package com.breynisson.router.digitalme;

import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import com.breynisson.router.mcp.SummarizeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
public class SemanticSearch {

    private static final int SNIPPET_CHARS = 2_000;
    private static final int FINAL_RESULT_LIMIT = 50;

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

    /** Returns up to FINAL_RESULT_LIMIT semantically similar results; empty list if Ollama is unavailable. */
    public List<SearchResult> search(String query) {
        return embeddingIndex.findSimilar(query, FINAL_RESULT_LIMIT).stream()
                .filter(r -> !ExclusionRules.isExcluded(r.sourceUrl()))
                .map(r -> {
                    Path p = Path.of(r.filePath());
                    return new SearchResult(r.sourceUrl(), p.getFileName().toString(),
                            chunkSnippet(r.chunkText()), (double) r.score());
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
        return normalizeAndTruncate(body);
    }

    /** Normalises and caps an already-extracted chunk of text (no header line to strip). */
    public static String chunkSnippet(String chunkText) {
        return normalizeAndTruncate(chunkText);
    }

    private static String normalizeAndTruncate(String body) {
        boolean truncated = body.length() > SNIPPET_CHARS;
        if (truncated) body = body.substring(0, SNIPPET_CHARS);
        String result = body.replace("\\n", " ").replace("\\t", " ").replace("\\r", " ")
                            .replaceAll("\\s+", " ").strip();
        return truncated ? result + " <truncated, use fetch tool>" : result;
    }
}
