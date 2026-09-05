package com.breynisson.router;

import com.breynisson.router.jdbc.PostgresTestSupport;
import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionIndexerTest {

    static String schema;

    @TempDir
    Path dataDir;

    @BeforeAll
    static void setUpDatabase() {
        schema = PostgresTestSupport.createIsolatedSchema("claudesessionindexer");
    }

    @AfterAll
    static void tearDownDatabase() {
        PostgresTestSupport.dropSchema(schema);
    }

    /**
     * MCP_EMBEDDING.EMBEDDING is declared extensions.VECTOR(768) NOT NULL (digital-me-db-1.sql) and
     * pgvector enforces that exact dimension on insert. The EmbeddingClient lambda below returns a
     * short test vector for readability, so it's zero-padded to 768 dims via {@link #v} before
     * EmbeddingIndex normalizes/stores it. Zero-padding both sides of a cosine comparison leaves the
     * dot product and magnitude (and so the cosine score) identical to the unpadded vectors, so this
     * doesn't change what any test asserts.
     */
    private static final int DIMENSIONS = 768;

    private static float[] v(float... values) {
        float[] padded = new float[DIMENSIONS];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    @Test
    void buildFileNameUsesDayHourMinuteSecondPrefixLikeOtherMcpResources() {
        LocalDateTime sessionStart = LocalDateTime.of(2026, 7, 16, 16, 15, 13);

        String fileName = ClaudeSessionIndexer.buildFileName("digital-me", sessionStart);

        assertEquals("16-16-15-13-claudecode-digital-me.txt", fileName);
    }

    @Test
    void deleteOldResourceFilesRemovesCachedSummaryForThatSource() {
        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        ClaudeSessionIndexer indexer = new ClaudeSessionIndexer(embeddingIndex, dataDir.toString(), "");
        String sourceUrl = "claude://some-project/some-session";
        SummaryCacheDao.upsert(sourceUrl, "cached summary");

        indexer.deleteOldResourceFiles(sourceUrl);

        assertNull(SummaryCacheDao.find(sourceUrl));
    }

    @Test
    void indexAllScansOnlyTheConfiguredProjectsDirectory(@TempDir Path claudeProjectsDir) throws Exception {
        Path projectDir = claudeProjectsDir.resolve("fixture-project");
        Files.createDirectories(projectDir);
        String sourceUrl = "claude://fixture-project/22222222-2222-2222-2222-222222222222";
        Path jsonlFile = projectDir.resolve("22222222-2222-2222-2222-222222222222.jsonl");
        Files.writeString(jsonlFile,
                "{\"type\":\"user\",\"timestamp\":\"2026-07-10T10:00:00Z\",\"isSidechain\":false,"
                        + "\"message\":{\"content\":\"What is the capital of Iceland?\"}}\n"
                        + "{\"type\":\"assistant\",\"timestamp\":\"2026-07-10T10:00:05Z\",\"isSidechain\":false,"
                        + "\"message\":{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"Reykjavik.\"}]}}\n");
        makeQuiet(jsonlFile);

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        ClaudeSessionIndexer indexer = new ClaudeSessionIndexer(embeddingIndex, dataDir.toString(),
                claudeProjectsDir.toString());

        indexer.indexAll();

        assertFalse(TextEntryDao.findByName(sourceUrl).isEmpty());
    }

    @Test
    void indexAllSkipsSessionsStillBeingActivelyWritten(@TempDir Path claudeProjectsDir) throws Exception {
        Path projectDir = claudeProjectsDir.resolve("fixture-project");
        Files.createDirectories(projectDir);
        String sourceUrl = "claude://fixture-project/33333333-3333-3333-3333-333333333333";
        Files.writeString(projectDir.resolve("33333333-3333-3333-3333-333333333333.jsonl"),
                "{\"type\":\"user\",\"timestamp\":\"2026-07-10T10:00:00Z\",\"isSidechain\":false,"
                        + "\"message\":{\"content\":\"Still typing\"}}\n");
        // file left with a fresh (just-now) mtime, simulating an actively-growing session

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        ClaudeSessionIndexer indexer = new ClaudeSessionIndexer(embeddingIndex, dataDir.toString(),
                claudeProjectsDir.toString());

        indexer.indexAll();

        assertTrue(TextEntryDao.findByName(sourceUrl).isEmpty());
    }

    @Test
    void indexAllSkipsReembeddingWhenContentIsUnchangedSinceLastIndex(@TempDir Path claudeProjectsDir) throws Exception {
        Path projectDir = claudeProjectsDir.resolve("fixture-project");
        Files.createDirectories(projectDir);
        Path jsonlFile = projectDir.resolve("44444444-4444-4444-4444-444444444444.jsonl");
        String userTurn = "{\"type\":\"user\",\"timestamp\":\"2026-07-10T10:00:00Z\",\"isSidechain\":false,"
                + "\"message\":{\"content\":\"What is the capital of Iceland?\"}}\n";
        Files.writeString(jsonlFile, userTurn);
        makeQuiet(jsonlFile);

        AtomicInteger embedCalls = new AtomicInteger();
        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> {
            embedCalls.incrementAndGet();
            return v(1f, 0f);
        }, dataDir.toString());
        ClaudeSessionIndexer indexer = new ClaudeSessionIndexer(embeddingIndex, dataDir.toString(),
                claudeProjectsDir.toString());

        indexer.indexAll();
        int callsAfterFirstIndex = embedCalls.get();
        assertTrue(callsAfterFirstIndex > 0);

        // Append a sidechain-only event: advances mtime but parseJsonl filters it out,
        // so the extracted content is byte-for-byte identical to before.
        Files.writeString(jsonlFile, userTurn
                + "{\"type\":\"user\",\"timestamp\":\"2026-07-10T10:05:00Z\",\"isSidechain\":true,"
                + "\"message\":{\"content\":\"internal tool turn\"}}\n");
        makeQuiet(jsonlFile);

        indexer.indexAll();

        assertEquals(callsAfterFirstIndex, embedCalls.get());
    }

    /** Backdates a file's mtime past the indexer's quiet period so it's treated as no longer actively written. */
    private static void makeQuiet(Path file) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 600_000));
    }
}
