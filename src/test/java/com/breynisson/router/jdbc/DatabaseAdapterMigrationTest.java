package com.breynisson.router.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseAdapterMigrationTest {

    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("dbmigration");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
    }

    @Test
    void db3ScriptCanBeRerunAgainstAnAlreadyMigratedSchema() throws Exception {
        String path = "/tmp/db3-rerun.txt";
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH = '" + path + "'");
        DatabaseAdapter.runSql("INSERT INTO MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT, MIGRATED_AT) "
                + "VALUES ('" + path + "', 0, 'http://x', 'text', array_fill(0, ARRAY[768])::real[]::extensions.vector, 'm', '2026-01-01T00:00:00Z', '2026-02-02T00:00:00Z')");

        String script;
        try (InputStream in = DatabaseAdapterMigrationTest.class.getResourceAsStream("/digital-me-db-3.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> DatabaseAdapter.runSql(script));

        String migratedAt = DatabaseAdapter.selectOne(
                "SELECT MIGRATED_AT FROM MCP_EMBEDDING WHERE FILE_PATH = ?", DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, path);
        assertEquals("2026-02-02T00:00:00Z", migratedAt);
        assertEquals("3", ApplicationMetadataDao.getValue("database.version"));
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH = '" + path + "'");
    }
}
