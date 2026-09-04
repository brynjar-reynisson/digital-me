package com.breynisson.router.jdbc;

import com.breynisson.router.RouterException;
import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DatabaseAdapter {

    public static ResultSetStringTransform RESULT_SET_STRING_TRANSFORM = new ResultSetStringTransform();
    public static ResultSetIntTransform RESULT_SET_INT_TRANSFORM = new ResultSetIntTransform();

    private static String host = "localhost";
    private static int port = 54322;
    private static String database = "postgres";
    private static String user = "postgres";
    private static String password = "postgres";
    private static String schema = "digitalme";
    private static HikariDataSource dataSource;

    /** Points DatabaseAdapter at a Postgres instance/schema, creating the schema if it doesn't exist. */
    public static void configure(String host, int port, String database, String user, String password, String schema) {
        if (!schema.matches("[a-z_][a-z0-9_]*")) {
            throw new RouterException("Invalid schema name: " + schema);
        }
        DatabaseAdapter.host = host;
        DatabaseAdapter.port = port;
        DatabaseAdapter.database = database;
        DatabaseAdapter.user = user;
        DatabaseAdapter.password = password;
        DatabaseAdapter.schema = schema;
        ensureSchemaExists();
        rebuildDataSource();
    }

    private static void ensureSchemaExists() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RouterException(e);
        }
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password);
             Statement stmt = c.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (SQLException e) {
            throw new RouterException("Could not create schema " + schema, e);
        }
    }

    private static void rebuildDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        config.setUsername(user);
        config.setPassword(password);
        config.setSchema(schema);
        dataSource = new HikariDataSource(config);
    }

    public static Connection getConnection() {
        if (dataSource == null) {
            rebuildDataSource();
        }
        try {
            Connection connection = dataSource.getConnection();
            PGvector.addVectorType(connection);
            return connection;
        } catch (SQLException e) {
            throw new RouterException(e);
        }
    }

    public static void safeClose(AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static Instant timeToInstant(String time) {
        return Instant.parse(time);
    }

    public static String instantToTime(Instant instant) {
        return instant.toString();
    }


    /**************************************************************************
     * Generic query and update mechanisms
     **************************************************************************/

    public interface ResultSetTransform<T> {
        List<T> transform(ResultSet rset) throws SQLException;
    }

    public static class ResultSetStringTransform implements ResultSetTransform<String> {

        @Override
        public List<String> transform(ResultSet rset) throws SQLException {
            List<String> list = new ArrayList<>();
            while (rset.next()) {
                list.add(rset.getString(1));
            }
            return list;
        }
    }

    public static class ResultSetIntTransform implements ResultSetTransform<Integer> {

        @Override
        public List<Integer> transform(ResultSet rset) throws SQLException {
            List<Integer> list = new ArrayList<>();
            while (rset.next()) {
                list.add(rset.getInt(1));
            }
            return list;
        }
    }

    public static <T> List<T> selectList(String sql, ResultSetTransform<T> resultSetTransform, Object... parameters) {

        Connection c = null;
        PreparedStatement pstmt = null;
        ResultSet rset = null;
        try {
            c = getConnection();
            pstmt = c.prepareStatement(sql);
            for (int i = 0; i < parameters.length; i++) {
                pstmt.setObject(i + 1, parameters[i]);
            }
            rset = pstmt.executeQuery();
            return resultSetTransform.transform(rset);
        } catch (Exception e) {
            throw new RouterException("Could not select using sql:\n" + sql, e);
        } finally {
            safeClose(rset, pstmt, c);
        }
    }

    public static <T> T selectOne(String sql, ResultSetTransform<T> resultSetTransform, Object... parameters) {
        List<T> result = selectList(sql, resultSetTransform, parameters);
        if (!result.isEmpty()) {
            return result.get(0);
        } else {
            return null;
        }
    }

    public static void runSql(String sqlScript) {

        Connection c = null;
        Statement stmt = null;
        String curStmt = null;

        try {
            c = getConnection();
            stmt = c.createStatement();
            String[] sqlStatements = sqlScript.split(";");
            for (String sqlStatement : sqlStatements) {
                sqlStatement = sqlStatement.trim();
                if (sqlStatement.isEmpty() || "END".equalsIgnoreCase(sqlStatement) || isCommentOnly(sqlStatement)) {
                    continue;
                }
                if (sqlStatement.toUpperCase().contains("BEGIN\r\n")) {
                    sqlStatement += "; END";
                }

                curStmt = sqlStatement;
                stmt.execute(sqlStatement);
            }
        } catch (Exception e) {
            if (curStmt != null && curStmt.length() > 50) {
                curStmt = curStmt.substring(0, 50);
            }
            throw new RouterException("Could not run sql for schema " + schema + ", current statement=\n" + curStmt, e);
        } finally {
            safeClose(stmt, c);
        }
    }

    /**
     * A statement is comment-only if every non-blank line starts with "--". A statement that
     * merely begins with a leading "--" documentation comment before real SQL (e.g. the
     * CREATE EXTENSION statement's preceding explanatory comment in digital-me-db-1.sql) must
     * still execute — Postgres itself parses embedded "--" comments in a statement just fine.
     */
    private static boolean isCommentOnly(String statement) {
        for (String line : statement.split("\r?\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("--")) {
                return false;
            }
        }
        return true;
    }

    public static void runPreparedStatement(String sql, Object... params) {

        Connection c = null;
        PreparedStatement pstmt = null;
        try {
            c = getConnection();
            pstmt = c.prepareStatement(sql);
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }
            pstmt.executeUpdate();
        } catch (Exception e) {
            throw new RouterException("Could not run prepared statement:\n" + sql + "\nWith params: " + Arrays.asList(params), e);
        } finally {
            safeClose(pstmt, c);
        }
    }


    /**
     * Set up the database schema, all tables and necessary initialization data
     */
    public static void init() {

        Set<String> resources = new LinkedHashSet<>();
        int max;
        for (int i = 1;; i++) {
            String resource = "/digital-me-db-" + i + ".sql";
            InputStream in = DatabaseAdapter.class.getResourceAsStream(resource);
            if (in != null) {
                resources.add(resource);
            } else {
                max = i - 1;
                break;
            }
        }


        List<String> tables = selectList(
                "SELECT tablename FROM pg_tables WHERE schemaname = current_schema()",
                new ResultSetStringTransform());
        if (!tables.isEmpty()) {
            if (tables.contains("application_metadata")) {
                String curVersionName = selectOne("SELECT VALUE FROM APPLICATION_METADATA WHERE KEY='database.version'", new ResultSetStringTransform());
                if (curVersionName == null) {
                    curVersionName = "0";
                }
                int curVersion = Integer.parseInt(curVersionName);
                for (int i = 1; i <= curVersion; i++) {
                    //all resources between version 1 and the current version have already been run
                    resources.remove("/digital-me-db-" + i + ".sql");
                }
            }
        }

        for (String resource : resources) {
            String sqlScript = inputStreamToString(Objects.requireNonNull(DatabaseAdapter.class.getResourceAsStream(resource)));
            runSql(sqlScript);
        }

        runPreparedStatement("UPDATE APPLICATION_METADATA SET VALUE=? WHERE KEY='database.version';", max);
    }

    private static String inputStreamToString(InputStream inputStream) {
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            byteOut.writeBytes(inputStream.readAllBytes());
            return byteOut.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Can't convert input stream to string", e);
        }
    }

}
