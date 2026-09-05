package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;
import com.pgvector.PGvector;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class McpEmbeddingDao {

    private static final String TABLE = "MCP_EMBEDDING";
    private static final McpEmbedding.ResultSetTransform TRANSFORM = new McpEmbedding.ResultSetTransform();
    private static final ScoredMatchTransform SCORED_MATCH_TRANSFORM = new ScoredMatchTransform();

    public static Set<String> findAllFilePaths() {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT DISTINCT FILE_PATH FROM " + TABLE,
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM));
    }

    public static Set<String> findFilePathsBySourceUrl(String sourceUrl) {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT DISTINCT FILE_PATH FROM " + TABLE + " WHERE SOURCE_URL = ?",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, sourceUrl));
    }

    public static void upsert(McpEmbedding embedding) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + TABLE + " (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (FILE_PATH, CHUNK_INDEX) DO UPDATE SET "
              + "SOURCE_URL = EXCLUDED.SOURCE_URL, CHUNK_TEXT = EXCLUDED.CHUNK_TEXT, EMBEDDING = EXCLUDED.EMBEDDING, "
              + "MODEL = EXCLUDED.MODEL, INDEXED_AT = EXCLUDED.INDEXED_AT",
                embedding.filePath, embedding.chunkIndex, embedding.sourceUrl, embedding.chunkText,
                new PGvector(embedding.embedding), embedding.model, embedding.indexedAt);
    }

    public static void deleteByFilePath(String filePath) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE FILE_PATH = ?", filePath);
    }

    public static void deleteBySourceUrl(String sourceUrl) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE SOURCE_URL = ?", sourceUrl);
    }

    public static void deleteByModelNot(String currentModel) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE MODEL <> ?", currentModel);
    }

    public static int countIndexedFiles() {
        Integer count = DatabaseAdapter.selectOne(
                "SELECT COUNT(DISTINCT FILE_PATH) FROM " + TABLE,
                DatabaseAdapter.RESULT_SET_INT_TRANSFORM);
        return count != null ? count : 0;
    }

    public static int countTotalChunks() {
        Integer count = DatabaseAdapter.selectOne(
                "SELECT COUNT(*) FROM " + TABLE,
                DatabaseAdapter.RESULT_SET_INT_TRANSFORM);
        return count != null ? count : 0;
    }

    /**
     * Returns the top-K chunks most similar to queryVector, deduplicated to each source URL's
     * single best-scoring chunk, filtered to scores >= minScore, ranked best-first.
     */
    public static List<ScoredMatch> findSimilar(float[] queryVector, float minScore, int topK) {
        // The vector extension (and its <=> operator) is installed into the "extensions" schema
        // (digital-me-db-1.sql), not "public" — and this DAO's connections run with search_path
        // set to just the app/test schema (DatabaseAdapter/PostgresTestSupport), so the bare
        // operator is unresolvable ("operator does not exist: extensions.vector <=> extensions.vector",
        // confirmed live). OPERATOR(extensions.<=>) schema-qualifies it explicitly, the same way
        // the DDL already schema-qualifies extensions.VECTOR(768) and extensions.vector_cosine_ops.
        PGvector vector = new PGvector(queryVector);
        return DatabaseAdapter.selectList(
                "SELECT file_path, source_url, chunk_text, score FROM ("
              + "  SELECT DISTINCT ON (source_url) file_path, source_url, chunk_text, "
              + "         1 - (embedding OPERATOR(extensions.<=>) ?) AS score "
              + "  FROM " + TABLE + " "
              + "  ORDER BY source_url, embedding OPERATOR(extensions.<=>) ?"
              + ") best_per_source "
              + "WHERE score >= ? "
              + "ORDER BY score DESC "
              + "LIMIT ?",
                SCORED_MATCH_TRANSFORM, vector, vector, minScore, topK);
    }

    public record ScoredMatch(String filePath, String sourceUrl, float score, String chunkText) {}

    private static class ScoredMatchTransform implements DatabaseAdapter.ResultSetTransform<ScoredMatch> {
        @Override
        public List<ScoredMatch> transform(ResultSet rset) throws SQLException {
            List<ScoredMatch> list = new ArrayList<>();
            while (rset.next()) {
                list.add(new ScoredMatch(rset.getString(1), rset.getString(2), rset.getFloat(4), rset.getString(3)));
            }
            return list;
        }
    }
}
