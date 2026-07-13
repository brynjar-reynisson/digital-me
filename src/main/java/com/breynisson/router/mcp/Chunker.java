package com.breynisson.router.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into ~{@link #TARGET_CHUNK_CHARS}-char windows, snapping each chunk's end
 * back to the nearest full sentence within {@link #BOUNDARY_LOOKBACK_CHARS} chars, instead of
 * cutting a sentence in half. The next chunk starts at that same boundary, so the deferred
 * sentence becomes the first sentence of the next chunk — chunks partition the text cleanly with
 * no duplicated or dropped characters.
 */
class Chunker {

    static final int TARGET_CHUNK_CHARS = 2000;
    static final int BOUNDARY_LOOKBACK_CHARS = 500;

    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int naturalEnd = Math.min(start + TARGET_CHUNK_CHARS, len);
            int end = naturalEnd;
            if (naturalEnd < len) {
                int boundary = lastSentenceBoundary(text, start, naturalEnd);
                if (boundary > start) {
                    end = boundary;
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    /** Returns the index just after the last sentence-ending punctuation before naturalEnd, or -1 if none found. */
    private static int lastSentenceBoundary(String text, int start, int naturalEnd) {
        int lookbackStart = Math.max(start, naturalEnd - BOUNDARY_LOOKBACK_CHARS);
        for (int i = naturalEnd - 1; i >= lookbackStart; i--) {
            char c = text.charAt(i);
            boolean isSentenceEnd = c == '.' || c == '!' || c == '?';
            if (isSentenceEnd && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }
}
