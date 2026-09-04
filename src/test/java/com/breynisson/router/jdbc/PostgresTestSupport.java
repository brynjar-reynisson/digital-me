package com.breynisson.router.jdbc;

import com.breynisson.router.RouterException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Test isolation for Postgres: each test class gets its own freshly-generated schema in the
 * shared "postgres" database (the Supabase local dev stack's instance, also used by
 * agent-suite/soulman), created fresh and dropped afterward — replacing the old per-test-class
 * SQLite @TempDir file pattern. Requires that instance (with pgvector available) running at
 * localhost:54322 with the default postgres/postgres credentials.
 */
public final class PostgresTestSupport {

    private static final String HOST = "localhost";
    private static final int PORT = 54322;
    private static final String DATABASE = "postgres";
    private static final String USER = "postgres";
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "postgres");

    private PostgresTestSupport() {
    }

    public static String createIsolatedSchema(String namePrefix) {
        String schema = namePrefix + "_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseAdapter.configure(HOST, PORT, DATABASE, USER, PASSWORD, schema);
        DatabaseAdapter.init();
        return schema;
    }

    public static void dropSchema(String schema) {
        String jdbcUrl = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE;
        try (Connection c = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
             Statement stmt = c.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (SQLException e) {
            throw new RouterException("Could not drop schema " + schema, e);
        }
    }
}
