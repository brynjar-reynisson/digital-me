package com.breynisson.router.mcp;

/**
 * Tries {@code primary} first; falls back to {@code fallback} only when the primary
 * returns {@code null} (unavailable, errored, timed out, or rate-limited).
 */
public class FallbackSummarizeClient implements SummarizeClient {

    private final SummarizeClient primary;
    private final SummarizeClient fallback;

    public FallbackSummarizeClient(SummarizeClient primary, SummarizeClient fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String summarize(String text) {
        String result = primary.summarize(text);
        return result != null ? result : fallback.summarize(text);
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || fallback.isAvailable();
    }
}
