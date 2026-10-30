package com.breynisson.router.jdbc;

import com.breynisson.router.RouterException;
import com.breynisson.router.jdbc.model.McpEmbedding;
import com.pgvector.PGvector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpEmbeddingDaoTest {

    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("mcpembeddingdao");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
    }

    /**
     * MCP_EMBEDDING.EMBEDDING is declared extensions.VECTOR(768) NOT NULL (digital-me-db-1.sql) and
     * pgvector enforces that exact dimension on insert (confirmed live: inserting a 2- or 3-element
     * vector fails with "ERROR: expected 768 dimensions, not 2"), unlike the old SQLite BLOB column
     * which accepted any length. Every test vector is zero-padded to 768 dims so inserts succeed;
     * zero-padding both sides of a cosine comparison leaves the dot product and magnitude (and so
     * the cosine score) identical to the unpadded vectors, so this doesn't change what any test
     * asserts.
     */
    private static final int DIMENSIONS = 768;

    private static float[] pad(float[] values) {
        float[] padded = new float[DIMENSIONS];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    private static float[] embeddingBytes(float... values) {
        return pad(values);
    }

    private static McpEmbedding embedding(String path, int chunkIndex, String sourceUrl, String chunkText, String model) {
        return new McpEmbedding(path, chunkIndex, sourceUrl, chunkText, embeddingBytes(1.0f, 2.0f), model, "2026-01-01T00:00:00Z");
    }

    private static void cleanup(String filePath) {
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH='" + filePath + "'");
    }

    /**
     * findAll() was removed from McpEmbeddingDao's public surface (Task 7); this direct query
     * replaces it for tests that need to inspect stored rows (including the raw embedding vector)
     * for a specific file path.
     */
    private static List<McpEmbedding> findByFilePath(String filePath) {
        return DatabaseAdapter.selectList(
                "SELECT FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING FROM MCP_EMBEDDING WHERE FILE_PATH = ?",
                new McpEmbedding.ResultSetTransform(), filePath);
    }

    @Test
    void upsertAndFindAll() {
        String path = "/tmp/dao-test-1.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/1", "chunk text", "nomic-embed-text"));

        List<McpEmbedding> all = findByFilePath(path);
        assertTrue(all.stream().anyMatch(e -> e.filePath.equals(path) && e.sourceUrl.equals("http://example.com/1")));
        cleanup(path);
    }

    @Test
    void findAllFilePathsReturnsDistinctPathAcrossChunks() {
        String path = "/tmp/dao-test-2.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/2", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/2", "chunk 1", "nomic-embed-text"));

        Set<String> paths = McpEmbeddingDao.findAllFilePaths();
        assertTrue(paths.contains(path));
        assertEquals(1, paths.stream().filter(p -> p.equals(path)).count());
        cleanup(path);
    }

    @Test
    void upsertReplacesSameChunkIndex() {
        String path = "/tmp/dao-test-3.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://old.com", "old text", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 0, "http://new.com", "new text", "nomic-embed-text"));

        List<McpEmbedding> matching = findByFilePath(path);
        assertEquals(1, matching.size());
        assertEquals("http://new.com", matching.get(0).sourceUrl);
        cleanup(path);
    }

    @Test
    void differentChunkIndexesForSameFileAreSeparateRows() {
        String path = "/tmp/dao-test-5.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/5", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/5", "chunk 1", "nomic-embed-text"));

        List<McpEmbedding> matching = findByFilePath(path);
        assertEquals(2, matching.size());
        cleanup(path);
    }

    @Test
    void findAllEmbeddingRoundTrips() {
        String path = "/tmp/dao-test-4.txt";
        float[] original = {0.1f, 0.5f, -0.3f};
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://example.com/4", "chunk text",
                embeddingBytes(original), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        McpEmbedding stored = findByFilePath(path).stream().findFirst().orElseThrow();
        assertArrayEquals(pad(original), stored.embedding, 0.0001f);
        cleanup(path);
    }

    @Test
    void deleteByFilePathRemovesAllChunksForFile() {
        String path = "/tmp/dao-test-6.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/6", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/6", "chunk 1", "nomic-embed-text"));

        McpEmbeddingDao.deleteByFilePath(path);

        assertTrue(findByFilePath(path).isEmpty());
    }

    @Test
    void findFilePathsBySourceUrlReturnsDistinctPathsAcrossFilesAndChunks() {
        String pathA = "/tmp/dao-test-8-a.txt";
        String pathB = "/tmp/dao-test-8-b.txt";
        String otherPath = "/tmp/dao-test-8-other.txt";
        McpEmbeddingDao.upsert(embedding(pathA, 0, "http://shared-source.com", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(pathA, 1, "http://shared-source.com", "chunk 1", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(pathB, 0, "http://shared-source.com", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(otherPath, 0, "http://other-source.com", "chunk 0", "nomic-embed-text"));

        Set<String> paths = McpEmbeddingDao.findFilePathsBySourceUrl("http://shared-source.com");

        assertEquals(Set.of(pathA, pathB), paths);
        cleanup(pathA);
        cleanup(pathB);
        cleanup(otherPath);
    }

    @Test
    void findFilePathsBySourceUrlReturnsEmptyForUnknownSource() {
        assertTrue(McpEmbeddingDao.findFilePathsBySourceUrl("http://never-indexed.com").isEmpty());
    }

    @Test
    void deleteBySourceUrlRemovesAllFilesAndChunksForThatSource() {
        String pathA = "/tmp/dao-test-9-a.txt";
        String pathB = "/tmp/dao-test-9-b.txt";
        String otherPath = "/tmp/dao-test-9-other.txt";
        McpEmbeddingDao.upsert(embedding(pathA, 0, "http://shared-source-2.com", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(pathB, 0, "http://shared-source-2.com", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(otherPath, 0, "http://other-source-2.com", "chunk 0", "nomic-embed-text"));

        McpEmbeddingDao.deleteBySourceUrl("http://shared-source-2.com");

        assertTrue(McpEmbeddingDao.findFilePathsBySourceUrl("http://shared-source-2.com").isEmpty());
        assertFalse(McpEmbeddingDao.findAllFilePaths().stream().anyMatch(p -> p.equals(pathA) || p.equals(pathB)));
        assertTrue(McpEmbeddingDao.findAllFilePaths().contains(otherPath));
        cleanup(otherPath);
    }

    @Test
    void deleteByModelNotRemovesOnlyMismatchedRows() {
        String keepPath = "/tmp/dao-test-7-keep.txt";
        String dropPath = "/tmp/dao-test-7-drop.txt";
        McpEmbeddingDao.upsert(embedding(keepPath, 0, "http://keep.com", "chunk", "current-model"));
        McpEmbeddingDao.upsert(embedding(dropPath, 0, "http://drop.com", "chunk", "old-model"));

        McpEmbeddingDao.deleteByModelNot("current-model");

        Set<String> remaining = McpEmbeddingDao.findAllFilePaths();
        assertTrue(remaining.contains(keepPath));
        assertFalse(remaining.contains(dropPath));
        cleanup(keepPath);
    }

    @Test
    void findSimilarDedupsToBestScoringChunkPerSource() {
        String path = "/tmp/dao-test-similar-1.txt";
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://similar.com", "weak chunk",
                pad(new float[]{0.0f, 1.0f}), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        McpEmbeddingDao.upsert(new McpEmbedding(path, 1, "http://similar.com", "strong chunk",
                pad(new float[]{1.0f, 0.0f}), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(pad(new float[]{1.0f, 0.0f}), 0f, 10);

        assertEquals(1, results.stream().filter(r -> r.sourceUrl().equals("http://similar.com")).count());
        assertEquals("strong chunk", results.stream()
                .filter(r -> r.sourceUrl().equals("http://similar.com")).findFirst().orElseThrow().chunkText());
        cleanup(path);
    }

    @Test
    void findSimilarAppliesMinScoreThreshold() {
        String path = "/tmp/dao-test-similar-2.txt";
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://orthogonal.com", "orthogonal chunk",
                pad(new float[]{0.0f, 1.0f}), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(pad(new float[]{1.0f, 0.0f}), 0.5f, 10);

        assertTrue(results.stream().noneMatch(r -> r.sourceUrl().equals("http://orthogonal.com")));
        cleanup(path);
    }

    private static float[] pad1024(float[] values) {
        float[] padded = new float[1024];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    private static McpEmbedding embedding1024(String path, int chunkIndex, String sourceUrl, String text) {
        return new McpEmbedding(path, chunkIndex, sourceUrl, text, pad1024(new float[]{1.0f, 0.0f}), "bge-m3", "2026-01-01T00:00:00Z");
    }

    private static void cleanupBoth(String filePath) {
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH='" + filePath + "'");
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING_V2 WHERE FILE_PATH='" + filePath + "'");
    }

    private static int countRows(String table, String filePath) {
        Integer n = DatabaseAdapter.selectOne("SELECT COUNT(*) FROM " + table + " WHERE FILE_PATH = ?",
                DatabaseAdapter.RESULT_SET_INT_TRANSFORM, filePath);
        return n == null ? 0 : n;
    }

    private static void insertV2Row(java.sql.Connection c, String path) throws SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO MCP_EMBEDDING_V2 (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) "
              + "VALUES (?, 0, 's', 't', ?, 'm', 'x')")) {
            ps.setString(1, path);
            ps.setObject(2, new PGvector(pad1024(new float[]{1f})));
            ps.executeUpdate();
        }
    }

    @Test
    void migrationSchemaExists() {
        String path = "/tmp/dao-test-schema.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://schema.com", "c", "nomic-embed-text"));
        DatabaseAdapter.runSql("UPDATE MCP_EMBEDDING SET MIGRATED_AT = '2026-01-01T00:00:00Z' WHERE FILE_PATH='" + path + "'");
        McpEmbeddingDao.upsert(McpEmbeddingDao.TABLE_V2, embedding1024(path, 0, "http://schema.com", "c"));
        assertEquals(1, countRows("MCP_EMBEDDING_V2", path));
        cleanupBoth(path);
    }

    @Test
    void inTransactionRollsBackOnException() {
        String rolledBack = "/tmp/dao-test-tx-rollback.txt";
        String committed = "/tmp/dao-test-tx-commit.txt";
        assertThrows(RouterException.class, () -> DatabaseAdapter.inTransaction(c -> {
            insertV2Row(c, rolledBack);
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, countRows("MCP_EMBEDDING_V2", rolledBack));

        String result = DatabaseAdapter.inTransaction(c -> {
            insertV2Row(c, committed);
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(1, countRows("MCP_EMBEDDING_V2", committed));
        cleanupBoth(committed);
    }

    @Test
    void upsertV2ThenFindSimilarV2ReturnsMatch() {
        String path = "/tmp/dao-test-v2-similar.txt";
        McpEmbeddingDao.upsert(McpEmbeddingDao.TABLE_V2, embedding1024(path, 0, "http://v2-similar.com", "v2 chunk"));
        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(
                McpEmbeddingDao.TABLE_V2, pad1024(new float[]{1.0f, 0.0f}), 0f, 10, false);
        assertTrue(results.stream().anyMatch(r -> r.sourceUrl().equals("http://v2-similar.com")));
        cleanupBoth(path);
    }

    @Test
    void findSimilarUnmigratedOnlySkipsMarkedRows() {
        String marked = "/tmp/dao-test-unmig-marked.txt";
        String open = "/tmp/dao-test-unmig-open.txt";
        McpEmbeddingDao.upsert(embedding(marked, 0, "http://unmig-marked.com", "c", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(open, 0, "http://unmig-open.com", "c", "nomic-embed-text"));
        DatabaseAdapter.runSql("UPDATE MCP_EMBEDDING SET MIGRATED_AT = '2026-01-01T00:00:00Z' WHERE FILE_PATH='" + marked + "'");
        float[] q = embeddingBytes(1.0f, 2.0f);

        List<McpEmbeddingDao.ScoredMatch> only = McpEmbeddingDao.findSimilar(McpEmbeddingDao.TABLE, q, 0f, 1000, true);
        assertTrue(only.stream().anyMatch(r -> r.sourceUrl().equals("http://unmig-open.com")));
        assertTrue(only.stream().noneMatch(r -> r.sourceUrl().equals("http://unmig-marked.com")));

        List<McpEmbeddingDao.ScoredMatch> all = McpEmbeddingDao.findSimilar(McpEmbeddingDao.TABLE, q, 0f, 1000, false);
        assertTrue(all.stream().anyMatch(r -> r.sourceUrl().equals("http://unmig-open.com")));
        assertTrue(all.stream().anyMatch(r -> r.sourceUrl().equals("http://unmig-marked.com")));
        cleanupBoth(marked);
        cleanupBoth(open);
    }

    @Test
    void deleteByFilePathAndSourceUrlRemoveV2Rows() {
        String path = "/tmp/dao-test-del-both-a.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://del-both-a.com", "c", "nomic-embed-text"));
        McpEmbeddingDao.upsert(McpEmbeddingDao.TABLE_V2, embedding1024(path, 0, "http://del-both-a.com", "c"));
        McpEmbeddingDao.deleteByFilePath(path);
        assertEquals(0, countRows("MCP_EMBEDDING", path));
        assertEquals(0, countRows("MCP_EMBEDDING_V2", path));

        String path2 = "/tmp/dao-test-del-both-b.txt";
        McpEmbeddingDao.upsert(embedding(path2, 0, "http://del-both-b.com", "c", "nomic-embed-text"));
        McpEmbeddingDao.upsert(McpEmbeddingDao.TABLE_V2, embedding1024(path2, 0, "http://del-both-b.com", "c"));
        McpEmbeddingDao.deleteBySourceUrl("http://del-both-b.com");
        assertEquals(0, countRows("MCP_EMBEDDING", path2));
        assertEquals(0, countRows("MCP_EMBEDDING_V2", path2));
    }

    @Test
    void reUpsertOfMigratedRowResetsMarker() {
        String path = "/tmp/dao-test-reset-marker.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://reset.com", "c", "nomic-embed-text"));
        DatabaseAdapter.runSql("UPDATE MCP_EMBEDDING SET MIGRATED_AT = '2026-01-01T00:00:00Z' WHERE FILE_PATH='" + path + "'");
        McpEmbeddingDao.upsert(embedding(path, 0, "http://reset.com", "c2", "nomic-embed-text"));
        Integer nulls = DatabaseAdapter.selectOne(
                "SELECT COUNT(*) FROM MCP_EMBEDDING WHERE FILE_PATH = ? AND MIGRATED_AT IS NULL",
                DatabaseAdapter.RESULT_SET_INT_TRANSFORM, path);
        assertEquals(1, nulls);
        cleanup(path);
    }

    @Test
    void findSimilarLimitsToTopK() {
        String pathA = "/tmp/dao-test-similar-3-a.txt";
        String pathB = "/tmp/dao-test-similar-3-b.txt";
        McpEmbeddingDao.upsert(new McpEmbedding(pathA, 0, "http://topk-a.com", "chunk a",
                pad(new float[]{1.0f, 0.0f}), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        McpEmbeddingDao.upsert(new McpEmbedding(pathB, 0, "http://topk-b.com", "chunk b",
                pad(new float[]{1.0f, 0.0f}), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(pad(new float[]{1.0f, 0.0f}), 0f, 1);

        assertEquals(1, results.size());
        cleanup(pathA);
        cleanup(pathB);
    }
}
