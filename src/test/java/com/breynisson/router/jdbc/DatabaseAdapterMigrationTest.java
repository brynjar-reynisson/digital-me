package com.breynisson.router.jdbc;

import com.breynisson.router.RouterException;
import com.breynisson.router.jdbc.model.McpEmbedding;
import com.pgvector.PGvector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseAdapterMigrationTest {

    private static final String FILE = "/tmp/db4-v2-row.txt";

    private static String embeddingColumnType() {
        return DatabaseAdapter.selectOne(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
              + "WHERE attrelid = to_regclass('MCP_EMBEDDING') AND attname = 'embedding'",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM);
    }

    private static String rowFingerprint(String table) {
        return DatabaseAdapter.selectOne(
                "SELECT FILE_PATH || '|' || CHUNK_INDEX || '|' || SOURCE_URL || '|' || CHUNK_TEXT || '|' "
              + "|| MODEL || '|' || INDEXED_AT || '|' || EMBEDDING::text FROM " + table,
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM);
    }

    private static int count(String sql) {
        return DatabaseAdapter.selectOne(sql, DatabaseAdapter.RESULT_SET_INT_TRANSFORM);
    }

    @Test
    void db4DropsLegacyAndKeepsV2DataAsMcpEmbedding() {
        String schema = PostgresTestSupport.createIsolatedSchemaAtVersion("db4data", 3);
        try {
            assertEquals("3", ApplicationMetadataDao.getValue("database.version"));
            DatabaseAdapter.runSql("INSERT INTO MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT, MIGRATED_AT) "
                    + "VALUES ('/tmp/db4-legacy.txt', 0, 'http://legacy.com', 'legacy', array_fill(0, ARRAY[768])::real[]::extensions.vector, 'nomic-embed-text', '2026-01-01T00:00:00Z', '2026-02-02T00:00:00Z')");
            float[] v2 = new float[1024];
            v2[0] = 1.0f;
            v2[5] = 0.25f;
            v2[1023] = -0.5f;
            DatabaseAdapter.runPreparedStatement(
                    "INSERT INTO MCP_EMBEDDING_V2 (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) "
                  + "VALUES (?, 3, 'http://v2.com', 'v2 text', ?, 'bge-m3', '2026-03-04T05:06:07Z')",
                    FILE, new PGvector(v2));
            String before = rowFingerprint("MCP_EMBEDDING_V2");

            DatabaseAdapter.init();

            assertEquals("4", ApplicationMetadataDao.getValue("database.version"));
            assertEquals("extensions.vector(1024)", embeddingColumnType());
            assertEquals(0, count("SELECT COUNT(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'mcp_embedding_v2'"));
            assertEquals(1, count("SELECT COUNT(*) FROM MCP_EMBEDDING"));
            assertEquals(0, count("SELECT COUNT(*) FROM MCP_EMBEDDING WHERE FILE_PATH = '/tmp/db4-legacy.txt'"));
            assertEquals(1, count("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() "
                    + "AND tablename = 'mcp_embedding' AND indexname = 'mcp_embedding_hnsw_idx' AND indexdef ILIKE '%hnsw%'"));
            List<McpEmbeddingDao.ScoredMatch> hits = McpEmbeddingDao.findSimilar(v2, 0.5f, 5);
            assertEquals(1, hits.size());
            assertEquals("http://v2.com", hits.get(0).sourceUrl());
            assertEquals(Set.of(FILE), McpEmbeddingDao.findAllFilePaths());

            // every column of the V2 row survives byte-for-byte (chunk_text, model, indexed_at, chunk_index, vector)
            String after = rowFingerprint("MCP_EMBEDDING");
            assertEquals(before, after);
            assertTrue(after.contains("v2 text") && after.contains("bge-m3") && after.contains("2026-03-04T05:06:07Z"));
            assertEquals(3, count("SELECT CHUNK_INDEX FROM MCP_EMBEDDING"));

            // constraint/index names match a fresh install
            assertEquals(1, count("SELECT COUNT(*) FROM pg_constraint WHERE conrelid = to_regclass('MCP_EMBEDDING') "
                    + "AND contype = 'p' AND conname = 'mcp_embedding_pkey'"));
            assertEquals(1, count("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() "
                    + "AND tablename = 'mcp_embedding' AND indexname = 'mcp_embedding_hnsw_idx'"));
            assertEquals(1, count("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() "
                    + "AND tablename = 'mcp_embedding' AND indexname = 'mcp_embedding_source_idx'"));

            // the renamed primary key still backs ON CONFLICT: upserting the same key twice updates, not fails
            assertDoesNotThrow(() -> McpEmbeddingDao.upsert(
                    new McpEmbedding(FILE, 3, "http://v2.com", "updated text", v2, "bge-m3", "2026-05-05T00:00:00Z")));
            assertDoesNotThrow(() -> McpEmbeddingDao.upsert(
                    new McpEmbedding(FILE, 3, "http://v2.com", "updated again", v2, "bge-m3", "2026-06-06T00:00:00Z")));
            assertEquals(1, count("SELECT COUNT(*) FROM MCP_EMBEDDING"));
            assertEquals("updated again", DatabaseAdapter.selectOne(
                    "SELECT CHUNK_TEXT FROM MCP_EMBEDDING", DatabaseAdapter.RESULT_SET_STRING_TRANSFORM));
        } finally {
            PostgresTestSupport.dropSchema(schema);
        }
    }

    @Test
    void db4IsAtomicAndNotReappliedUnsafely() {
        String schema = PostgresTestSupport.createIsolatedSchema("db4atomic");
        try {
            float[] vec = new float[1024];
            vec[0] = 1.0f;
            McpEmbeddingDao.upsert(new McpEmbedding(FILE, 0, "http://v2.com", "text", vec, "bge-m3", "2026-01-01T00:00:00Z"));
            DatabaseAdapter.runPreparedStatement("UPDATE APPLICATION_METADATA SET VALUE='3' WHERE KEY='database.version'");

            assertThrows(RouterException.class, DatabaseAdapter::init);

            assertEquals("3", ApplicationMetadataDao.getValue("database.version"));
            assertEquals("extensions.vector(1024)", embeddingColumnType());
            assertEquals(1, count("SELECT COUNT(*) FROM MCP_EMBEDDING"));
        } finally {
            PostgresTestSupport.dropSchema(schema);
        }
    }

    @Test
    void freshDatabaseEndsAtVersion4WithSingle1024Table() {
        String schema = PostgresTestSupport.createIsolatedSchema("db4fresh");
        try {
            assertEquals("4", ApplicationMetadataDao.getValue("database.version"));
            assertEquals("extensions.vector(1024)", embeddingColumnType());
            assertEquals(0, count("SELECT COUNT(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'mcp_embedding_v2'"));
            assertEquals(0, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                    + "AND table_name = 'mcp_embedding' AND column_name = 'migrated_at'"));
            assertEquals(2, count("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'mcp_embedding' "
                    + "AND indexname IN ('mcp_embedding_hnsw_idx', 'mcp_embedding_source_idx')"));
            assertDoesNotThrow(DatabaseAdapter::init); // idempotent once at the latest version
        } finally {
            PostgresTestSupport.dropSchema(schema);
        }
    }
}
