package com.breynisson.router.migration;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One-time tool that copies every row out of the old SQLite database into the already-schema'd
 * Postgres database DatabaseAdapter is configured against. Run once via:
 * java -jar digital-me-0.1.jar --digitalme.migrate-sqlite-path=&lt;path to digital-me.db&gt;
 * The old SQLite file is never modified. Not idempotent — re-running against a non-empty
 * Postgres database will duplicate or conflict on primary keys.
 */
@Component
@ConditionalOnProperty(name = "digitalme.migrate-sqlite-path")
public class SqliteToPostgresMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SqliteToPostgresMigrator.class);

    // MCP_EMBEDDING.EMBEDDING is extensions.VECTOR(768) NOT NULL, and pgvector enforces that
    // exact dimension on insert. Legacy SQLite embeddings all came from nomic-embed-text and are
    // already 768-dim in practice, so this is a no-op for real data — it exists purely as a
    // defensive backstop against any short/legacy vector that would otherwise make the whole
    // migration abort partway through on a single bad row.
    private static final int EMBEDDING_DIMENSIONS = 768;

    private final String sqlitePath;

    public SqliteToPostgresMigrator(@Value("${digitalme.migrate-sqlite-path}") String sqlitePath) {
        this.sqlitePath = sqlitePath;
    }

    @Override
    public void run(String... args) throws Exception {
        migrate();
        System.exit(0);
    }

    void migrate() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String jdbcUrl = "jdbc:sqlite:" + sqlitePath.replace("\\", "/");
        try (Connection sqlite = DriverManager.getConnection(jdbcUrl)) {
            migrateTextEntry(sqlite);
            migrateTextEntryMetadata(sqlite);
            migrateMcpEmbedding(sqlite);
            migrateSummaryCache(sqlite);
            migrateAddContentQueue(sqlite);
        }
        log.info("Migration from {} complete.", sqlitePath);
    }

    private void migrateTextEntry(Connection sqlite) throws SQLException {
        int count = 0;
        try (Statement stmt = sqlite.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT UUID, TIME, NAME FROM TEXT_ENTRY")) {
            while (rs.next()) {
                DatabaseAdapter.runPreparedStatement(
                        "INSERT INTO TEXT_ENTRY (UUID, TIME, NAME) VALUES (?, ?, ?)",
                        rs.getString(1), rs.getString(2), rs.getString(3));
                count++;
            }
        }
        log.info("Migrated {} TEXT_ENTRY rows", count);
    }

    private void migrateTextEntryMetadata(Connection sqlite) throws SQLException {
        int count = 0;
        try (Statement stmt = sqlite.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT TEXT_ENTRY_UUID, KEY, VALUE FROM TEXT_ENTRY_METADATA")) {
            while (rs.next()) {
                DatabaseAdapter.runPreparedStatement(
                        "INSERT INTO TEXT_ENTRY_METADATA (TEXT_ENTRY_UUID, KEY, VALUE) VALUES (?, ?, ?)",
                        rs.getString(1), rs.getString(2), rs.getString(3));
                count++;
            }
        }
        log.info("Migrated {} TEXT_ENTRY_METADATA rows", count);
    }

    private void migrateMcpEmbedding(Connection sqlite) throws SQLException {
        int count = 0;
        try (Statement stmt = sqlite.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT FROM MCP_EMBEDDING")) {
            while (rs.next()) {
                float[] vector = padToDimensions(unpackFloats(rs.getBytes(5)));
                DatabaseAdapter.runPreparedStatement(
                        "INSERT INTO MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        new PGvector(vector), rs.getString(6), rs.getString(7));
                count++;
            }
        }
        log.info("Migrated {} MCP_EMBEDDING rows", count);
    }

    private void migrateSummaryCache(Connection sqlite) throws SQLException {
        int count = 0;
        try (Statement stmt = sqlite.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT SOURCE_URL, SUMMARY, CREATED_AT FROM SUMMARY_CACHE")) {
            while (rs.next()) {
                DatabaseAdapter.runPreparedStatement(
                        "INSERT INTO SUMMARY_CACHE (SOURCE_URL, SUMMARY, CREATED_AT) VALUES (?, ?, ?)",
                        rs.getString(1), rs.getString(2), rs.getString(3));
                count++;
            }
        }
        log.info("Migrated {} SUMMARY_CACHE rows", count);
    }

    private void migrateAddContentQueue(Connection sqlite) throws SQLException {
        int count = 0;
        try (Statement stmt = sqlite.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS FROM ADD_CONTENT_QUEUE")) {
            while (rs.next()) {
                DatabaseAdapter.runPreparedStatement(
                        "INSERT INTO ADD_CONTENT_QUEUE (UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS) VALUES (?, ?, ?, ?)",
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4));
                count++;
            }
        }
        log.info("Migrated {} ADD_CONTENT_QUEUE rows", count);
    }

    private static float[] unpackFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) floats[i] = buf.getFloat();
        return floats;
    }

    /**
     * Zero-pads (or, in principle, truncates) a legacy embedding to exactly EMBEDDING_DIMENSIONS
     * so it satisfies Postgres's fixed-width extensions.VECTOR(768) column. A no-op for the
     * already-768-dim vectors every real nomic-embed-text embedding produces.
     */
    private static float[] padToDimensions(float[] vector) {
        if (vector.length == EMBEDDING_DIMENSIONS) {
            return vector;
        }
        float[] padded = new float[EMBEDDING_DIMENSIONS];
        System.arraycopy(vector, 0, padded, 0, Math.min(vector.length, EMBEDDING_DIMENSIONS));
        return padded;
    }
}
