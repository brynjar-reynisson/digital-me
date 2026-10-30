package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.PostgresTestSupport;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingMigratorTest {

    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("embeddingmigrator");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
    }

    @BeforeEach
    void clean() {
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING");
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING_V2");
    }

    private static float[] vec(int dims) {
        float[] v = new float[dims];
        v[0] = 1f;
        return v;
    }

    private static void addOld(String file, String source, String... chunks) {
        for (int i = 0; i < chunks.length; i++) {
            McpEmbeddingDao.upsert(new McpEmbedding(file, i, source, chunks[i], vec(768), "nomic", "2024-01-01T00:00:00Z"));
        }
    }

    private static V2Settings settings(EmbeddingClient client) {
        return new V2Settings(client, "bge-m3", "doc:", "q:", 0f);
    }

    private static int count(String table, String where) {
        return DatabaseAdapter.selectOne("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                DatabaseAdapter.RESULT_SET_INT_TRANSFORM);
    }

    @Test
    void migrateBatchCommitsEachFileAndMarksChunks() {
        addOld("/a", "http://x.com/a", "a0", "a1");
        addOld("/b", "http://x.com/b", "b0");
        addOld("/c", "http://x.com/c", "c0");
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> vec(1024)));
        assertEquals(3, m.migrateBatch(10));
        assertEquals(4, count("MCP_EMBEDDING_V2", "MODEL = 'bge-m3'"));
        assertEquals(0, McpEmbeddingDao.migrationStatus().remainingFiles());
    }

    @Test
    void migrateBatchEmbedsStoredChunkTextWithV2DocumentPrefix() {
        addOld("/a", "http://x.com/a", "hello");
        List<String> seen = new CopyOnWriteArrayList<>();
        new EmbeddingMigrator(settings(t -> {
            seen.add(t);
            return vec(1024);
        })).migrateBatch(10);
        assertEquals(List.of("doc: hello"), seen);
    }

    @Test
    void embedFailureLeavesFileUntouchedAndOthersProceed() {
        addOld("/a", "http://x.com/a", "good1");
        addOld("/b", "http://x.com/b", "bad");
        addOld("/c", "http://x.com/c", "good2");
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> t.contains("bad") ? null : vec(1024)));
        assertEquals(2, m.migrateBatch(10));
        assertEquals(0, count("MCP_EMBEDDING_V2", "FILE_PATH = '/b'"));
        assertEquals(1, count("MCP_EMBEDDING", "FILE_PATH = '/b' AND MIGRATED_AT IS NULL"));
        assertEquals(1, McpEmbeddingDao.migrationStatus().remainingFiles());
    }

    @Test
    void ollamaDownCommitsNothingAndReturnsZero() {
        addOld("/a", "http://x.com/a", "a0");
        assertEquals(0, new EmbeddingMigrator(settings(t -> null)).migrateBatch(10));
        assertEquals(0, count("MCP_EMBEDDING_V2", "TRUE"));
    }

    @Test
    void resumesAfterInterruption() {
        addOld("/a", "http://x.com/a", "a0");
        addOld("/b", "http://x.com/b", "b0");
        addOld("/c", "http://x.com/c", "c0");
        int[] calls = {0};
        EmbeddingMigrator crashing = new EmbeddingMigrator(settings(t -> {
            if (++calls[0] == 2) throw new IllegalStateException("crash");
            return vec(1024);
        }));
        try {
            crashing.migrateBatch(10);
        } catch (RuntimeException ignored) {
            // simulated crash
            calls[0] = -1;
        }
        new EmbeddingMigrator(settings(t -> vec(1024))).migrateBatch(10);
        assertEquals(0, McpEmbeddingDao.migrationStatus().remainingFiles());
        assertEquals(3, count("MCP_EMBEDDING_V2", "TRUE"));
    }

    @Test
    void fileDeletedMidMigrationLeavesNoV2Rows() {
        addOld("/a", "http://x.com/a", "a0");
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> {
            McpEmbeddingDao.deleteByFilePath("/a");
            return vec(1024);
        }));
        assertEquals(0, m.migrateBatch(10));
        assertEquals(0, count("MCP_EMBEDDING_V2", "TRUE"));
    }

    @Test
    void prioritisesLocalFilesBeforeWebPages() {
        addOld("/web", "http://x.com/w", "web");
        addOld("/local", "C:/notes/n.txt", "local");
        List<String> order = new ArrayList<>();
        new EmbeddingMigrator(settings(t -> {
            order.add(t);
            return vec(1024);
        })).migrateBatch(10);
        assertEquals(List.of("doc: local", "doc: web"), order);
    }

    private static class MutableClock extends java.time.Clock {
        java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    @Test
    void poisonHeadFilesDoNotStarveLaterFiles() {
        addOld("/p1", "C:/p1.txt", "poison1");
        addOld("/p2", "C:/p2.txt", "poison2");
        addOld("/h1", "http://x.com/h1", "healthy1");
        addOld("/h2", "http://x.com/h2", "healthy2");
        java.util.concurrent.atomic.AtomicInteger poisonCalls = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> {
            if (t.contains("poison")) {
                poisonCalls.incrementAndGet();
                return null;
            }
            return vec(1024);
        }), new MutableClock());
        assertEquals(0, m.migrateBatch(2));
        assertEquals(0, m.migrateBatch(2));
        assertEquals(0, m.migrateBatch(2));
        assertEquals(6, poisonCalls.get());
        assertEquals(2, m.migrateBatch(2));
        assertEquals(6, poisonCalls.get());
        assertEquals(2, count("MCP_EMBEDDING_V2", "FILE_PATH IN ('/h1','/h2')"));
    }

    @Test
    void penalisedFileIsRetriedAfterPenaltyExpires() {
        addOld("/p", "C:/p.txt", "poison");
        MutableClock clock = new MutableClock();
        boolean[] working = {false};
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> {
            calls.incrementAndGet();
            return working[0] ? vec(1024) : null;
        }), clock);
        for (int i = 0; i < 3; i++) m.migrateBatch(5);
        assertEquals(3, calls.get());
        assertEquals(0, m.migrateBatch(5));
        assertEquals(3, calls.get());
        working[0] = true;
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(11));
        assertEquals(1, m.migrateBatch(5));
        assertEquals(4, calls.get());
    }

    @Test
    void successResetsFailureCount() {
        addOld("/p", "C:/p.txt", "x");
        boolean[] working = {false};
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> working[0] ? vec(1024) : null), new MutableClock());
        m.migrateBatch(5);
        m.migrateBatch(5);
        working[0] = true;
        assertEquals(1, m.migrateBatch(5));
        addOld("/p", "C:/p.txt", "x");
        working[0] = false;
        m.migrateBatch(5);
        m.migrateBatch(5);
        working[0] = true;
        assertEquals(1, m.migrateBatch(5));
    }

    @Test
    void pausesChunkEmbeddingWhileInteractiveSearchActive() {
        addOld("/a", "http://x.com/a", "a0", "a1");
        List<String> events = new CopyOnWriteArrayList<>();
        int[] polls = {0};
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> {
            events.add("embed");
            return vec(1024);
        }), java.time.Clock.systemUTC(), () -> {
            events.add("poll");
            return ++polls[0] <= 3;
        }, 1);
        assertEquals(1, m.migrateBatch(10));
        assertEquals(List.of("poll", "poll", "poll", "poll", "embed", "poll", "embed"), events);
        assertEquals(0, McpEmbeddingDao.migrationStatus().remainingFiles());
    }

    @Test
    void pausingDoesNotPenaliseFile() {
        addOld("/a", "http://x.com/a", "a0");
        int[] polls = {0};
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> vec(1024)),
                java.time.Clock.systemUTC(), () -> ++polls[0] <= 50, 1);
        for (int i = 0; i < EmbeddingMigrator.PENALTY_THRESHOLD + 1; i++) {
            addOld("/a", "http://x.com/a", "a0");
            assertEquals(1, m.migrateBatch(10));
            polls[0] = 0;
        }
    }

    @Test
    void interruptDuringPauseAbortsFileWithoutCommit() {
        addOld("/a", "http://x.com/a", "a0");
        int[] embeds = {0};
        EmbeddingMigrator m = new EmbeddingMigrator(settings(t -> {
            embeds[0]++;
            return vec(1024);
        }), java.time.Clock.systemUTC(), () -> true, 1);
        Thread.currentThread().interrupt();
        try {
            assertEquals(0, m.migrateBatch(10));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, embeds[0]);
        assertEquals(1, McpEmbeddingDao.migrationStatus().remainingFiles());
    }
}
