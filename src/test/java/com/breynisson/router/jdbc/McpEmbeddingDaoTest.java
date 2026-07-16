package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpEmbeddingDaoTest {

    @TempDir
    static Path dbDir;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    private static byte[] embeddingBytes(float... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES);
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    private static McpEmbedding embedding(String path, int chunkIndex, String sourceUrl, String chunkText, String model) {
        return new McpEmbedding(path, chunkIndex, sourceUrl, chunkText, embeddingBytes(1.0f, 2.0f), model, "2026-01-01T00:00:00Z");
    }

    private static void cleanup(String filePath) {
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH='" + filePath + "'");
    }

    @Test
    void upsertAndFindAll() {
        String path = "/tmp/dao-test-1.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/1", "chunk text", "nomic-embed-text"));

        List<McpEmbedding> all = McpEmbeddingDao.findAll();
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

        List<McpEmbedding> matching = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(path)).toList();
        assertEquals(1, matching.size());
        assertEquals("http://new.com", matching.get(0).sourceUrl);
        cleanup(path);
    }

    @Test
    void differentChunkIndexesForSameFileAreSeparateRows() {
        String path = "/tmp/dao-test-5.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/5", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/5", "chunk 1", "nomic-embed-text"));

        List<McpEmbedding> matching = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(path)).toList();
        assertEquals(2, matching.size());
        cleanup(path);
    }

    @Test
    void findAllEmbeddingBytesRoundTrip() {
        String path = "/tmp/dao-test-4.txt";
        float[] original = {0.1f, 0.5f, -0.3f};
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://example.com/4", "chunk text",
                embeddingBytes(original), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        McpEmbedding stored = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(path)).findFirst().orElseThrow();
        ByteBuffer buf = ByteBuffer.wrap(stored.embedding);
        assertArrayEquals(original, new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat()}, 0.0001f);
        cleanup(path);
    }

    @Test
    void deleteByFilePathRemovesAllChunksForFile() {
        String path = "/tmp/dao-test-6.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/6", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/6", "chunk 1", "nomic-embed-text"));

        McpEmbeddingDao.deleteByFilePath(path);

        assertTrue(McpEmbeddingDao.findAll().stream().noneMatch(e -> e.filePath.equals(path)));
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
}
