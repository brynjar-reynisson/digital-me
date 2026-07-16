package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import com.breynisson.router.mcp.SummarizeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        List<EmbeddingIndex.ScoredResult> matches = embeddingIndex.findSimilar(query, FINAL_RESULT_LIMIT).stream()
                .filter(r -> !ExclusionRules.isExcluded(r.sourceUrl()))
                .toList();
        // name must stay the internal mcp-resources filename (the MCP fetch tool looks files up by it);
        // displayName carries the original human-friendly name, looked up once for the whole result set.
        Map<String, String> displayNames = LuceneIndex.findNamesBySources(
                matches.stream().map(EmbeddingIndex.ScoredResult::sourceUrl).collect(Collectors.toSet()));
        return matches.stream()
                .map(r -> {
                    Path p = Path.of(r.filePath());
                    return new SearchResult(r.sourceUrl(), p.getFileName().toString(),
                            chunkSnippet(r.chunkText()), (double) r.score(), null, displayNames.get(r.sourceUrl()));
                })
                .toList();
    }

    /**
     * Summarizes the given text, caching the result per source; returns null if the
     * backend is unavailable. A null/empty result is never cached, so a failed call
     * is retried on the next request for that source rather than permanently
     * showing no summary. source may be null (e.g. a caller with no known file
     * identity), in which case the cache is never consulted or written.
     */
    public String summarize(String text, String source) {
        if (source != null) {
            String cached = SummaryCacheDao.find(source);
            if (cached != null) {
                return cached;
            }
        }
        String summary = summarizeClient.summarize(text);
        if (source != null && summary != null && !summary.isEmpty()) {
            SummaryCacheDao.upsert(source, summary);
        }
        return summary;
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
