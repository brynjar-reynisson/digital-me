package com.breynisson.router.jdbc;

import com.breynisson.router.RouterException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class DatabaseAdapter {

    public static ResultSetStringTransform RESULT_SET_STRING_TRANSFORM = new ResultSetStringTransform();
    public static ResultSetIntTransform RESULT_SET_INT_TRANSFORM = new ResultSetIntTransform();
    private static String defaultDatabasePath = getDefaultDatabasePath();

    private static String getDefaultDatabasePath() {
        if(defaultDatabasePath == null) {
            defaultDatabasePath = new File(".").getAbsolutePath() + File.separator + "digital-me.db";
        }
        return defaultDatabasePath;
    }

    public static void setDefaultDatabasePath(String defaultDatabasePath) {
        DatabaseAdapter.defaultDatabasePath = defaultDatabasePath;
        safeClose(connection);
        connection = null;
    }

    private static Connection connection = null;

    public static Connection getConnection() {
        if(isClosed(connection)) {
            connection = openSqliteConnection(defaultDatabasePath);
        }
        return connection;
    }

    private static boolean isClosed(Connection c) {
        if(c == null) {
            return true;
        }
        try {
            return c.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    public static Connection openSqliteConnection(String dbPath) {
        dbPath = dbPath.replaceAll("\\\\", "/");
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (Exception e) {
            throw new RouterException(e);
        }
        return c;
    }

    public static void safeClose(AutoCloseable ... closeables) {
        for(AutoCloseable closeable : closeables) {
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
            while(rset.next()) {
                list.add(rset.getString(1));
            }
            return list;
        }
    }

    public static class ResultSetIntTransform implements ResultSetTransform<Integer> {

        @Override
        public List<Integer> transform(ResultSet rset) throws SQLException {
            List<Integer> list = new ArrayList<>();
            while(rset.next()) {
                list.add(rset.getInt(1));
            }
            return list;
        }
    }

    public static <T> List<T> selectList(String sql, ResultSetTransform<T> resultSetTransform, Object ... parameters) {

        PreparedStatement pstmt = null;
        ResultSet rset = null;
        try {
            Connection c = getConnection();
            pstmt = c.prepareStatement(sql);
            for(int i=0; i<parameters.length; i++) {
                pstmt.setObject(i+1, parameters[i]);
            }
            rset = pstmt.executeQuery();
            return resultSetTransform.transform(rset);
        } catch (Exception e) {
            throw new RouterException("Could not select using sql:\n" + sql, e);
        } finally {
            safeClose(rset, pstmt);
        }
    }

    public static <T> T selectOne(String sql, ResultSetTransform<T> resultSetTransform, Object ... parameters) {
        List<T> result = selectList(sql, resultSetTransform, parameters);
        if (!result.isEmpty()) {
            return result.get(0);
        } else {
            return null;
        }
    }

    public static void runSql(String sqlScript) {

        Statement stmt = null;
        String curStmt = null;

        try {
            Connection c = getConnection();
            stmt = c.createStatement();
            String[] sqlStatements = sqlScript.split(";");
            for(String sqlStatement : sqlStatements) {
                sqlStatement = sqlStatement.trim();
                if(sqlStatement.isEmpty() || "END".equalsIgnoreCase(sqlStatement) || sqlStatement.startsWith("--")) {
                    continue;
                }
                if(sqlStatement.toUpperCase().contains("BEGIN\r\n")) {
                    sqlStatement += "; END";
                }

                curStmt = sqlStatement;
                stmt.execute(sqlStatement);
            }
        } catch (Exception e) {
            if(curStmt != null && curStmt.length() > 50) {
                curStmt = curStmt.substring(0, 50);
            }
            throw new RouterException("Could not run sql for " + defaultDatabasePath + ", current statement=\n" + curStmt, e);
        } finally {
            safeClose(stmt);
        }
    }

    public static void runPreparedStatement(String sql, Object ... params) {

        PreparedStatement pstmt = null;
        try {
            Connection c = getConnection();
            pstmt = c.prepareStatement(sql);
            for(int i=0; i<params.length; i++) {
                pstmt.setObject(i+1, params[i]);
            }
            pstmt.executeUpdate();
        } catch (Exception e) {
            throw new RouterException("Could not run prepared statement:\n" + sql + "\nWith params: " + Arrays.asList(params), e);
        } finally {
            safeClose(pstmt);
        }
    }



    /**
     * Set up the database schema, all tables and necessary initialization data
     */
    public static void init() {

        Set<String> resources = new LinkedHashSet<>();
        int max;
        for(int i=1;; i++) {
            String resource = "/digital-me-db-" + i + ".sql";
            InputStream in = DatabaseAdapter.class.getResourceAsStream(resource);
            if(in != null) {
                resources.add(resource);
            } else {
                max = i-1;
                break;
            }
        }


        List<String> tables = selectList("SELECT NAME FROM SQLITE_MASTER WHERE TYPE='table'", new ResultSetStringTransform());
        if(!tables.isEmpty()) {
            if(tables.contains("APPLICATION_METADATA")) {
                String curVersionName = selectOne("SELECT VALUE FROM APPLICATION_METADATA WHERE KEY='database.version'", new ResultSetStringTransform());
                if (curVersionName == null) {
                    curVersionName = "0";
                }
                int curVersion = Integer.parseInt(curVersionName);
                for(int i=1; i<=curVersion; i++) {
                    //all resources between version 1 and the current version have already been run
                    resources.remove("/digital-me-db-" + i + ".sql");
                }
            }
        }

        for(String resource : resources) {
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
