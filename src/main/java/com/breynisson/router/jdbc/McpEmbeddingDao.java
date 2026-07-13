package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;

import java.util.List;
import java.util.Set;

public class McpEmbeddingDao {

    private static final String TABLE = "MCP_EMBEDDING";
    private static final McpEmbedding.ResultSetTransform TRANSFORM = new McpEmbedding.ResultSetTransform();

    public static Set<String> findAllFilePaths() {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT DISTINCT FILE_PATH FROM " + TABLE,
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM));
    }

    public static List<McpEmbedding> findAll() {
        return DatabaseAdapter.selectList(
                "SELECT FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING FROM " + TABLE,
                TRANSFORM);
    }

    public static void upsert(McpEmbedding embedding) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT OR REPLACE INTO " + TABLE
                        + " (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) VALUES (?, ?, ?, ?, ?, ?, ?)",
                embedding.filePath, embedding.chunkIndex, embedding.sourceUrl, embedding.chunkText,
                embedding.embedding, embedding.model, embedding.indexedAt);
    }

    public static void deleteByFilePath(String filePath) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE FILE_PATH = ?", filePath);
    }

    public static void deleteByModelNot(String currentModel) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE MODEL <> ?", currentModel);
    }
}
