package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;

import java.util.List;
import java.util.Set;

public class McpEmbeddingDao {

    private static final String TABLE = "MCP_EMBEDDING";
    private static final McpEmbedding.ResultSetTransform TRANSFORM = new McpEmbedding.ResultSetTransform();

    public static Set<String> findAllFilePaths() {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT FILE_PATH FROM " + TABLE,
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM));
    }

    public static List<McpEmbedding> findAll() {
        return DatabaseAdapter.selectList(
                "SELECT FILE_PATH, SOURCE_URL, EMBEDDING FROM " + TABLE,
                TRANSFORM);
    }

    public static void upsert(McpEmbedding embedding) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT OR REPLACE INTO " + TABLE + " (FILE_PATH, SOURCE_URL, EMBEDDING, INDEXED_AT) VALUES (?, ?, ?, ?)",
                embedding.filePath, embedding.sourceUrl, embedding.embedding, embedding.indexedAt);
    }

    public static void deleteByFilePath(String filePath) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE FILE_PATH = ?", filePath);
    }
}
