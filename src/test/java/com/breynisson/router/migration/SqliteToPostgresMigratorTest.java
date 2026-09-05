package com.breynisson.router.migration;

import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.PostgresTestSupport;
import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.TextEntryMetadataDao;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SqliteToPostgresMigratorTest {

    static String schema;

    /**
     * MCP_EMBEDDING.EMBEDDING is declared extensions.VECTOR(768) NOT NULL, and pgvector enforces
     * that exact dimension on both insert and the <=> comparison operator used by findSimilar().
     * The fixture below deliberately seeds a 2-dimensional SQLite embedding to keep the test
     * simple; the query vector here is zero-padded to 768 dims to match what the migrator will
     * have stored, the same way McpEmbeddingDaoTest pads its own test vectors. Zero-padding both
     * sides of a cosine comparison leaves the score unchanged.
     */
    private static final int DIMENSIONS = 768;

    private static float[] pad(float[] values) {
        float[] padded = new float[DIMENSIONS];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    @BeforeAll
    static void setUpDatabase() {
        schema = PostgresTestSupport.createIsolatedSchema("sqlitetopostgresmigrator");
    }

    @AfterAll
    static void tearDownDatabase() {
        PostgresTestSupport.dropSchema(schema);
    }

    @AfterEach
    void cleanup() {
        for (String uuid : new String[]{"fixture-uuid"}) {
            TextEntryDao.delete(uuid);
        }
        McpEmbeddingDao.deleteByFilePath("/tmp/fixture.txt");
        SummaryCacheDao.deleteBySourceUrl("http://fixture.com");
    }

    private static String seedSqliteFixture(Path dbFile) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + dbFile.toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE TEXT_ENTRY (UUID VARCHAR(60) PRIMARY KEY, TIME VARCHAR(23), NAME TEXT)");
            stmt.execute("CREATE TABLE TEXT_ENTRY_METADATA (TEXT_ENTRY_UUID VARCHAR(60), KEY VARCHAR(1024), VALUE TEXT, PRIMARY KEY (TEXT_ENTRY_UUID, KEY))");
            stmt.execute("CREATE TABLE MCP_EMBEDDING (FILE_PATH TEXT, CHUNK_INDEX INTEGER, SOURCE_URL TEXT, CHUNK_TEXT TEXT, EMBEDDING BLOB, MODEL TEXT, INDEXED_AT TEXT, PRIMARY KEY (FILE_PATH, CHUNK_INDEX))");
            stmt.execute("CREATE TABLE SUMMARY_CACHE (SOURCE_URL TEXT PRIMARY KEY, SUMMARY TEXT, CREATED_AT TEXT)");
            stmt.execute("CREATE TABLE ADD_CONTENT_QUEUE (UUID VARCHAR(60) PRIMARY KEY, PAYLOAD TEXT, RECEIVED_AT TEXT, ATTEMPTS INTEGER)");

            stmt.execute("INSERT INTO TEXT_ENTRY VALUES ('fixture-uuid', '2026-01-01T00:00:00Z', '/tmp/fixture.txt')");
            stmt.execute("INSERT INTO TEXT_ENTRY_METADATA VALUES ('fixture-uuid', 'contentHash', 'abc123')");
            stmt.execute("INSERT INTO SUMMARY_CACHE VALUES ('http://fixture.com', 'a summary', '2026-01-01T00:00:00Z')");
            stmt.execute("INSERT INTO ADD_CONTENT_QUEUE VALUES ('queue-uuid', '{}', '2026-01-01T00:00:00Z', 0)");

            ByteBuffer buf = ByteBuffer.allocate(2 * Float.BYTES);
            buf.putFloat(0.6f);
            buf.putFloat(0.8f);
            try (var pstmt = c.prepareStatement(
                    "INSERT INTO MCP_EMBEDDING VALUES ('/tmp/fixture.txt', 0, 'http://fixture.com', 'chunk text', ?, 'nomic-embed-text', '2026-01-01T00:00:00Z')")) {
                pstmt.setBytes(1, buf.array());
                pstmt.executeUpdate();
            }
        }
        return url;
    }

    @Test
    void migratesAllTablesFromSqliteIntoPostgres(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("fixture.db");
        seedSqliteFixture(dbFile);

        new SqliteToPostgresMigrator(dbFile.toString()).migrate();

        assertNotNull(TextEntryDao.findByUUID("fixture-uuid"));
        assertEquals("abc123", TextEntryMetadataDao.get("fixture-uuid", "contentHash"));
        assertEquals("a summary", SummaryCacheDao.find("http://fixture.com"));
        assertEquals(1, AddContentQueueDao.findAllOrderedByReceivedAt().size());

        var matches = McpEmbeddingDao.findSimilar(pad(new float[]{0.6f, 0.8f}), 0f, 10);
        assertEquals(1, matches.size());
        assertEquals("http://fixture.com", matches.get(0).sourceUrl());
        assertEquals(1.0f, matches.get(0).score(), 0.001f,
                "migrated vector queried against itself should score a perfect cosine match");

        AddContentQueueDao.delete("queue-uuid");
    }
}
