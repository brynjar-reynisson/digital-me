package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingIndexTest {

    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("embeddingindex");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
    }

    @TempDir
    Path dataDir;

    /**
     * MCP_EMBEDDING.EMBEDDING is declared extensions.VECTOR(768) NOT NULL (digital-me-db-1.sql) and
     * pgvector enforces that exact dimension on insert. The EmbeddingClient lambdas below return
     * short test vectors (e.g. {1.0f, 0.0f}) for readability, so every one of them is zero-padded
     * to 768 dims via {@link #v} before EmbeddingIndex normalizes/stores it. Zero-padding both
     * sides of a cosine comparison leaves the dot product and magnitude (and so the cosine score)
     * identical to the unpadded vectors, so this doesn't change what any test asserts.
     */
    private static final int DIMENSIONS = 768;

    private static float[] v(float... values) {
        float[] padded = new float[DIMENSIONS];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    private static void cleanup(Path... files) {
        for (Path f : files)
            DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH='" + f.toAbsolutePath() + "'");
    }

    @Test
    void indexFileStoresEmbedding() throws Exception {
        Path file = dataDir.resolve("page.txt");
        Files.writeString(file, "http://example.com\nhello world");

        EmbeddingIndex index = new EmbeddingIndex(text -> v(1.0f, 0.0f), dataDir.toString());
        index.indexFile(file);

        assertTrue(McpEmbeddingDao.findAllFilePaths().contains(file.toAbsolutePath().toString()));
        cleanup(file);
    }

    @Test
    void indexFileSkipsWhenOllamaUnavailable() throws Exception {
        Path file = dataDir.resolve("unavailable.txt");
        Files.writeString(file, "http://example.com\ncontent");

        EmbeddingIndex index = new EmbeddingIndex(text -> null, dataDir.toString());
        index.indexFile(file);

        assertFalse(McpEmbeddingDao.findAllFilePaths().contains(file.toAbsolutePath().toString()));
    }

    @Test
    void indexAllSkipsAlreadyIndexedFiles() throws Exception {
        Path mcpDir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = mcpDir.resolve("once.txt");
        Files.writeString(file, "http://example.com\ncontent");

        AtomicInteger callCount = new AtomicInteger();
        EmbeddingIndex index = new EmbeddingIndex(text -> {
            callCount.incrementAndGet();
            return v(1.0f);
        }, dataDir.toString());

        index.indexAll();
        int afterFirst = callCount.get();
        index.indexAll(); // already indexed — should not call embed again

        assertEquals(afterFirst, callCount.get());
        cleanup(file);
    }

    @Test
    void findSimilarRanksCloserResultFirst() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path fileA = dir.resolve("a.txt");
        Path fileB = dir.resolve("b.txt");
        Files.writeString(fileA, "http://a.com\ndoc a");
        Files.writeString(fileB, "http://b.com\ndoc b");

        // fileA embedding [1,0], fileB embedding [0,1], query [1,0] → fileA scores 1.0, fileB scores 0.0
        EmbeddingIndex index = new EmbeddingIndex(text -> {
            if (text.contains("doc a")) return v(1.0f, 0.0f);
            if (text.contains("doc b")) return v(0.0f, 1.0f);
            return v(1.0f, 0.0f); // query
        }, dataDir.toString());

        index.indexFile(fileA);
        index.indexFile(fileB);

        List<EmbeddingIndex.ScoredResult> results = index.findSimilar("query", 2);
        assertEquals(2, results.size());
        assertEquals("http://a.com", results.get(0).sourceUrl());
        assertTrue(results.get(0).score() > results.get(1).score());
        cleanup(fileA, fileB);
    }

    @Test
    void findSimilarReturnsEmptyWhenOllamaUnavailable() {
        EmbeddingIndex index = new EmbeddingIndex(text -> null, dataDir.toString());
        assertTrue(index.findSimilar("query", 5).isEmpty());
    }

    @Test
    void indexFileChunksLongContentIntoMultipleEmbedCalls() throws Exception {
        Path file = dataDir.resolve("large.txt");
        String longBody = "Sentence. ".repeat(500); // ~5000 chars
        Files.writeString(file, "http://example.com\n" + longBody);

        List<String> embedded = new java.util.ArrayList<>();
        EmbeddingIndex index = new EmbeddingIndex(text -> {
            embedded.add(text);
            return v(1.0f);
        }, dataDir.toString());

        index.indexFile(file);

        assertTrue(embedded.size() > 1, "Expected multiple chunks to be embedded for long content");
        for (String text : embedded) {
            assertTrue(text.length() <= Chunker.TARGET_CHUNK_CHARS,
                    "Each embedded chunk should not exceed the target chunk size");
        }
        assertEquals(embedded.size(), McpEmbeddingDao.countTotalChunks());
        cleanup(file);
    }

    @Test
    void findSimilarAppliesScoreThreshold() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path fileA = dir.resolve("a.txt");
        Path fileB = dir.resolve("b.txt");
        Files.writeString(fileA, "http://a.com\nrelevant content");
        Files.writeString(fileB, "http://b.com\nirrelevant content");

        EmbeddingIndex index = new EmbeddingIndex(
                text -> {
                    if (text.contains("irrelevant content")) return v(0.0f, 1.0f);
                    if (text.contains("relevant content")) return v(1.0f, 0.0f);
                    return v(1.0f, 0.0f); // query
                },
                dataDir.toString(), "nomic-embed-text", "", "", 0.5f, "");

        index.indexFile(fileA);
        index.indexFile(fileB);

        List<EmbeddingIndex.ScoredResult> results = index.findSimilar("query", 10);
        assertEquals(1, results.size());
        assertEquals("http://a.com", results.get(0).sourceUrl());
        cleanup(fileA, fileB);
    }

    @Test
    void findSimilarDedupsToBestChunkPerFile() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("multi.txt");
        String longBody = "Weak match sentence. ".repeat(150) + "Strong match sentence. ".repeat(150);
        Files.writeString(file, "http://multi.com\n" + longBody);

        EmbeddingIndex index = new EmbeddingIndex(
                text -> {
                    if (text.contains("Strong match")) return v(1.0f, 0.0f);
                    if (text.contains("Weak match")) return v(0.0f, 1.0f);
                    return v(1.0f, 0.0f); // query
                },
                dataDir.toString(), "nomic-embed-text", "", "", 0f, "");

        index.indexFile(file);
        List<EmbeddingIndex.ScoredResult> results = index.findSimilar("query", 10);

        assertEquals(1, results.size(), "Multiple chunks of the same file should collapse to one result");
        assertEquals("http://multi.com", results.get(0).sourceUrl());
        assertEquals(1.0f, results.get(0).score(), 0.001f);
        cleanup(file);
    }

    @Test
    void findSimilarDedupsAcrossDifferentFilesWithSameSourceUrl() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path fileA = dir.resolve("a.txt");
        Path fileB = dir.resolve("b.txt");
        Files.writeString(fileA, "http://same-source.com\nfirst version content");
        Files.writeString(fileB, "http://same-source.com\nsecond version content");

        EmbeddingIndex index = new EmbeddingIndex(text -> v(1.0f, 0.0f), dataDir.toString());
        index.indexFile(fileA);
        index.indexFile(fileB);

        List<EmbeddingIndex.ScoredResult> results = index.findSimilar("query", 10);

        assertEquals(1, results.size(), "Two different files with the same sourceUrl should collapse to one result");
        assertEquals("http://same-source.com", results.get(0).sourceUrl());
        cleanup(fileA, fileB);
    }

    @Test
    void indexAllRemovesRowsForDeletedFiles() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("temp.txt");
        Files.writeString(file, "http://temp.com\ncontent");

        EmbeddingIndex index = new EmbeddingIndex(text -> v(1.0f), dataDir.toString());
        index.indexAll();
        assertTrue(McpEmbeddingDao.findAllFilePaths().contains(file.toAbsolutePath().toString()));

        Files.delete(file);
        index.indexAll();

        assertFalse(McpEmbeddingDao.findAllFilePaths().contains(file.toAbsolutePath().toString()));
        cleanup(file);
    }

    @Test
    void indexAllReembedsFilesWhenModelChanges() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("model-change.txt");
        Files.writeString(file, "http://model-change.com\ncontent");

        AtomicInteger callCount = new AtomicInteger();
        EmbeddingIndex oldModelIndex = new EmbeddingIndex(
                text -> {
                    callCount.incrementAndGet();
                    return v(1.0f);
                },
                dataDir.toString(), "old-model", "", "", 0f, "");
        oldModelIndex.indexAll();
        assertEquals(1, callCount.get());

        EmbeddingIndex newModelIndex = new EmbeddingIndex(
                text -> {
                    callCount.incrementAndGet();
                    return v(1.0f);
                },
                dataDir.toString(), "new-model", "", "", 0f, "");
        newModelIndex.indexAll();

        assertEquals(2, callCount.get(), "File should be re-embedded once the configured model changes");
        cleanup(file);
    }

    @Test
    void indexFileAppliesDocumentPrefix() throws Exception {
        Path file = dataDir.resolve("prefixed.txt");
        Files.writeString(file, "http://example.com\nhello world");

        String[] captured = {null};
        EmbeddingIndex index = new EmbeddingIndex(
                text -> {
                    captured[0] = text;
                    return v(1.0f);
                },
                dataDir.toString(), "nomic-embed-text", "search_document:", "search_query:", 0f, "");

        index.indexFile(file);

        assertEquals("search_document: hello world", captured[0]);
        cleanup(file);
    }

    @Test
    void findSimilarAppliesQueryPrefix() {
        String[] captured = {null};
        EmbeddingIndex index = new EmbeddingIndex(
                text -> {
                    captured[0] = text;
                    return null;
                },
                dataDir.toString(), "nomic-embed-text", "search_document:", "search_query:", 0f, "");

        index.findSimilar("hello", 5);

        assertEquals("search_query: hello", captured[0]);
    }
}
