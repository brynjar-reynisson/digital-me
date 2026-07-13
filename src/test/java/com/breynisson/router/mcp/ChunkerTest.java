package com.breynisson.router.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {

    private static String sentence(int n) {
        return "This is sentence number " + n + " in the test document, added to pad out the length nicely.";
    }

    private static String repeatedSentences(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(sentence(i)).append(" ");
        }
        return sb.toString().strip();
    }

    @Test
    void shortTextProducesSingleChunk() {
        String text = "Just one short sentence.";
        List<String> chunks = Chunker.chunk(text);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void longTextSplitsOnSentenceBoundaries() {
        String text = repeatedSentences(60); // ~90 chars/sentence, ~5400 chars total
        List<String> chunks = Chunker.chunk(text);

        assertTrue(chunks.size() > 1, "Expected multiple chunks for long text");
        for (int i = 0; i < chunks.size() - 1; i++) {
            String chunk = chunks.get(i);
            assertTrue(chunk.endsWith("."), "Non-final chunk should end at a sentence boundary: [" + chunk + "]");
            assertTrue(chunk.length() <= Chunker.TARGET_CHUNK_CHARS,
                    "Chunk should not exceed the target size: length=" + chunk.length());
        }
    }

    @Test
    void chunksReconstructOriginalTextExactly() {
        String text = repeatedSentences(60);
        List<String> chunks = Chunker.chunk(text);
        assertTrue(chunks.size() > 1);

        StringBuilder reconstructed = new StringBuilder();
        for (String chunk : chunks) reconstructed.append(chunk);

        assertEquals(text, reconstructed.toString(),
                "Chunks should partition the text cleanly at sentence boundaries with no duplicated or dropped characters");
    }

    @Test
    void noSentenceBoundaryFallsBackToHardCut() {
        String text = "x".repeat(5000); // no punctuation anywhere
        List<String> chunks = Chunker.chunk(text);

        assertTrue(chunks.size() > 1);
        assertEquals(Chunker.TARGET_CHUNK_CHARS, chunks.get(0).length(),
                "With no sentence boundary available, chunk should hard-cut at the target size");
        assertEquals(Chunker.TARGET_CHUNK_CHARS, chunks.get(1).length());
    }

    @Test
    void finalChunkCanBeShorterThanTarget() {
        String text = repeatedSentences(25);
        List<String> chunks = Chunker.chunk(text);
        String last = chunks.get(chunks.size() - 1);
        assertFalse(last.isEmpty());
        assertTrue(last.length() <= Chunker.TARGET_CHUNK_CHARS);
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(Chunker.chunk("").isEmpty());
    }
}
