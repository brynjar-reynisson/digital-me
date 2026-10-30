package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;
import com.pgvector.PGvector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class McpEmbeddingDao {

    public static final String TABLE = "MCP_EMBEDDING";
    public static final String TABLE_V2 = "MCP_EMBEDDING_V2";
    private static final McpEmbedding.ResultSetTransform TRANSFORM = new McpEmbedding.ResultSetTransform();
    private static final ScoredMatchTransform SCORED_MATCH_TRANSFORM = new ScoredMatchTransform();

    public static Set<String> findAllFilePaths() {
        return findAllFilePaths(TABLE);
    }

    public static Set<String> findAllFilePaths(String table) {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT DISTINCT FILE_PATH FROM " + table,
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM));
    }

    public static Set<String> findFilePathsBySourceUrl(String sourceUrl) {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT DISTINCT FILE_PATH FROM " + TABLE + " WHERE SOURCE_URL = ?",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, sourceUrl));
    }

    public static void upsert(McpEmbedding embedding) {
        upsert(TABLE, embedding);
    }

    public static void upsert(String table, McpEmbedding embedding) {
        // Re-embedding a row in the legacy table invalidates any earlier migration of that file
        String resetMarker = table.equals(TABLE) ? ", MIGRATED_AT = NULL" : "";
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + table + " (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (FILE_PATH, CHUNK_INDEX) DO UPDATE SET "
              + "SOURCE_URL = EXCLUDED.SOURCE_URL, CHUNK_TEXT = EXCLUDED.CHUNK_TEXT, EMBEDDING = EXCLUDED.EMBEDDING, "
              + "MODEL = EXCLUDED.MODEL, INDEXED_AT = EXCLUDED.INDEXED_AT" + resetMarker,
                embedding.filePath, embedding.chunkIndex, embedding.sourceUrl, embedding.chunkText,
                new PGvector(embedding.embedding), embedding.model, embedding.indexedAt);
    }

    public static void deleteByFilePath(String filePath) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE FILE_PATH = ?", filePath);
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE_V2 + " WHERE FILE_PATH = ?", filePath);
    }

    public static void deleteBySourceUrl(String sourceUrl) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE SOURCE_URL = ?", sourceUrl);
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE_V2 + " WHERE SOURCE_URL = ?", sourceUrl);
    }

    public static void deleteByModelNot(String currentModel) {
        deleteByModelNot(TABLE, currentModel);
    }

    public static void deleteByModelNot(String table, String currentModel) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + table + " WHERE MODEL <> ?", currentModel);
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
        return findSimilar(TABLE, queryVector, minScore, topK, false);
    }

    /**
     * Table-parametrised variant. When unmigratedOnly is true, rows already copied into
     * MCP_EMBEDDING_V2 (MIGRATED_AT set) are skipped; only meaningful for the legacy table.
     */
    public static List<ScoredMatch> findSimilar(String table, float[] queryVector, float minScore, int topK,
                                                boolean unmigratedOnly) {
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
              + "  FROM " + table + " "
              + (unmigratedOnly ? "  WHERE MIGRATED_AT IS NULL " : "")
              + "  ORDER BY source_url, embedding OPERATOR(extensions.<=>) ?"
              + ") best_per_source "
              + "WHERE score >= ? "
              + "ORDER BY score DESC "
              + "LIMIT ?",
                SCORED_MATCH_TRANSFORM, vector, vector, minScore, topK);
    }

    public record ChunkToMigrate(int chunkIndex, String sourceUrl, String chunkText) {}

    public record MigrationStatus(int migratedFiles, int remainingFiles, int migratedChunks, int remainingChunks) {}

    /**
     * Distinct file paths that still have unmigrated rows, highest priority first: tier 0 local
     * (non-http) sources, tier 1 Icelandic (.is host) pages, tier 2 everything else; newest first within a tier.
     */
    public static List<String> findNextUnmigratedFilePaths(int limit) {
        return DatabaseAdapter.selectList(
                "SELECT FILE_PATH FROM " + TABLE + " WHERE MIGRATED_AT IS NULL "
              + "GROUP BY FILE_PATH "
              + "ORDER BY MIN(CASE WHEN SOURCE_URL !~* '^https?://' THEN 0 "
              + "WHEN SOURCE_URL ~* '^https?://[^/]*\\.is(/|:|$)' THEN 1 ELSE 2 END), "
              + "MAX(INDEXED_AT) DESC, FILE_PATH "
              + "LIMIT ?",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, limit);
    }

    public static List<ChunkToMigrate> findUnmigratedChunks(String filePath) {
        return DatabaseAdapter.selectList(
                "SELECT CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT FROM " + TABLE
              + " WHERE FILE_PATH = ? AND MIGRATED_AT IS NULL ORDER BY CHUNK_INDEX",
                rset -> {
                    List<ChunkToMigrate> list = new ArrayList<>();
                    while (rset.next()) {
                        list.add(new ChunkToMigrate(rset.getInt(1), rset.getString(2), rset.getString(3)));
                    }
                    return list;
                }, filePath);
    }

    /**
     * Atomically writes a file's V2 rows and marks its legacy rows migrated. Returns false (and
     * writes nothing) if the legacy rows no longer match v2Rows in number, e.g. because the file
     * was re-indexed or deleted since its chunks were read, or it was already migrated.
     */
    public static boolean commitMigration(String filePath, List<McpEmbedding> v2Rows, String migratedAt) {
        return DatabaseAdapter.inTransaction(c -> {
            for (McpEmbedding row : v2Rows) {
                upsert(c, TABLE_V2, row, null);
            }
            // Per-row and text-sensitive: a chunk re-written since it was embedded no longer matches
            int updated = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + TABLE + " SET MIGRATED_AT = ? WHERE FILE_PATH = ? AND CHUNK_INDEX = ? "
                  + "AND CHUNK_TEXT = ? AND MIGRATED_AT IS NULL")) {
                for (McpEmbedding row : v2Rows) {
                    ps.setString(1, migratedAt);
                    ps.setString(2, filePath);
                    ps.setInt(3, row.chunkIndex);
                    ps.setString(4, row.chunkText);
                    updated += ps.executeUpdate();
                }
            }
            if (updated != v2Rows.size()) {
                c.rollback();
                return false;
            }
            return true;
        });
    }

    public static MigrationStatus migrationStatus() {
        MigrationStatus status = DatabaseAdapter.selectOne(
                "SELECT COUNT(DISTINCT FILE_PATH) FILTER (WHERE MIGRATED_AT IS NOT NULL), "
              + "COUNT(DISTINCT FILE_PATH) FILTER (WHERE MIGRATED_AT IS NULL), "
              + "COUNT(*) FILTER (WHERE MIGRATED_AT IS NOT NULL), "
              + "COUNT(*) FILTER (WHERE MIGRATED_AT IS NULL) FROM " + TABLE,
                rset -> {
                    List<MigrationStatus> list = new ArrayList<>();
                    while (rset.next()) {
                        list.add(new MigrationStatus(rset.getInt(1), rset.getInt(2), rset.getInt(3), rset.getInt(4)));
                    }
                    return list;
                });
        return status != null ? status : new MigrationStatus(0, 0, 0, 0);
    }

    /** Upsert on a caller-supplied connection; a non-null migratedAt is stored in the legacy table's MIGRATED_AT. */
    private static void upsert(Connection c, String table, McpEmbedding e, String migratedAt) throws SQLException {
        boolean legacy = table.equals(TABLE);
        String sql = "INSERT INTO " + table + " (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT"
              + (legacy ? ", MIGRATED_AT" : "") + ") VALUES (?, ?, ?, ?, ?, ?, ?" + (legacy ? ", ?" : "") + ") "
              + "ON CONFLICT (FILE_PATH, CHUNK_INDEX) DO UPDATE SET "
              + "SOURCE_URL = EXCLUDED.SOURCE_URL, CHUNK_TEXT = EXCLUDED.CHUNK_TEXT, EMBEDDING = EXCLUDED.EMBEDDING, "
              + "MODEL = EXCLUDED.MODEL, INDEXED_AT = EXCLUDED.INDEXED_AT" + (legacy ? ", MIGRATED_AT = EXCLUDED.MIGRATED_AT" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, e.filePath);
            ps.setInt(2, e.chunkIndex);
            ps.setString(3, e.sourceUrl);
            ps.setString(4, e.chunkText);
            ps.setObject(5, new PGvector(e.embedding));
            ps.setString(6, e.model);
            ps.setString(7, e.indexedAt);
            if (legacy) {
                ps.setString(8, migratedAt);
            }
            ps.executeUpdate();
        }
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
