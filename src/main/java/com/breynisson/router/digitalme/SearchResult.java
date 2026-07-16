package com.breynisson.router.digitalme;

import java.util.Map;
import java.util.Objects;

/** name is the internal mcp-resources filename (needed by the MCP fetch tool); displayName, when
 * present (semantic search only, looked up from the Lucene index), is the original human-friendly
 * name for the frontend to show instead. */
public record SearchResult(String source, String name, String snippet, Double score, Map<String, Integer> termFrequencies, String displayName) {

    public SearchResult(String source, String name) {
        this(source, name, null, null, null, null);
    }

    public SearchResult(String source, String name, String snippet) {
        this(source, name, snippet, null, null, null);
    }

    public SearchResult(String source, String name, String snippet, Double score) {
        this(source, name, snippet, score, null, null);
    }

    public SearchResult(String source, String name, String snippet, Double score, Map<String, Integer> termFrequencies) {
        this(source, name, snippet, score, termFrequencies, null);
    }

    public boolean equals(Object other) {
        if (other == null || !other.getClass().equals(this.getClass())) {
            return false;
        }
        SearchResult otherResult = (SearchResult) other;
        return Objects.equals(this.source, otherResult.source);
    }

    public int hashCode() {
        return source.hashCode();
    }
}
