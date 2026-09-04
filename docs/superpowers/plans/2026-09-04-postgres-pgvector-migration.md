# Postgres + pgvector Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all application storage (currently one SQLite file) to a local
Postgres database, storing embeddings in a `pgvector` column with an HNSW
index, and delete `EmbeddingIndex`'s unbounded in-memory vector cache.

**Architecture:** `DatabaseAdapter` keeps its existing static
`selectList`/`selectOne`/`runPreparedStatement`/`runSql`/`init` facade, but
internally swaps a single raw SQLite `Connection` for a HikariCP-pooled
Postgres `DataSource`. Every DAO's calling code is unchanged except three
`INSERT OR REPLACE` call sites (→ `ON CONFLICT ... DO UPDATE`) and
`McpEmbeddingDao`, whose embedding column becomes a native `vector` type
queried via pgvector's `<=>` operator instead of loaded into a JVM
`ConcurrentHashMap`. A one-time `CommandLineRunner` migrates existing SQLite
data into the new schema.

**Tech Stack:** Postgres (local instance, existing `postgres` superuser),
`pgvector` extension, `org.postgresql:postgresql` JDBC driver,
`com.pgvector:pgvector` Java helper, HikariCP (via
`spring-boot-starter-jdbc`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-04-postgres-pgvector-migration-design.md`

## Global Constraints

- `postgres.password` resolves from `${POSTGRES_PASSWORD:postgres}` — never
  commit a real secret; the literal `postgres` fallback is the accepted
  local default.
- SQLite is fully retired from the running application — `DatabaseAdapter`
  must not reference `org.sqlite.JDBC` anywhere except the one-time
  migration tool.
- Every DAO's public static method signatures stay the same except
  `McpEmbeddingDao` (`embedding` type `byte[]` → `float[]`, `findAll()`
  deleted, `findSimilar(...)` added) — no other DAO's public API changes.
- Every borrowed `Connection` must be closed (returned to the pool) in a
  `finally` block — unlike the old single-Connection SQLite design.
- Table/column identifiers stay unquoted uppercase in all SQL strings
  (matches existing DAO code unchanged; Postgres folds them to lowercase
  consistently on both write and read).
- The real local Postgres instance is Supabase's local dev stack (already
  running via Docker, serving `agent-suite` and `soulman`'s own schemas) —
  `127.0.0.1:54322`, database `postgres`, credentials `postgres`/`postgres`
  (confirmed working). digital-me does not get its own database — it gets
  its own schema (`digitalme`) inside that shared `postgres` database,
  matching how `agent-suite` (`projects_dev`/`projects_prod`/`projects_test`)
  and `soulman` (`memory_dev`/`memory_prod`) already do it there. pgvector
  0.8.0 is available on this instance but not yet enabled.
- Tests connect to the same shared instance, isolated per test class via a
  dedicated, freshly-generated schema (not `digitalme` itself) — no
  Testcontainers, no Docker dependency of their own.
- Run `mvn checkstyle:check` after any Java change (also enforced by the
  `PostToolUse` hook) — no unused imports, no `EqualsAvoidNull` violations,
  etc. (`checkstyle.xml`).
- Maven is not on PATH — invoke it via:
  `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" <goals>`

---

## Task 1: Dependencies and connection configuration

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Produces: Maven dependencies `org.postgresql:postgresql`,
  `com.pgvector:pgvector`, `spring-boot-starter-jdbc` available to all later
  tasks. Config keys `postgres.host`, `postgres.port`, `postgres.database`,
  `postgres.user`, `postgres.password`, `postgres.schema` available via
  Spring `@Value`.

- [ ] **Step 1: Look up current stable versions of the new dependencies**

Run (already allowlisted in this repo's settings):
```
WebFetch https://central.sonatype.com/artifact/org.postgresql/postgresql
WebFetch https://central.sonatype.com/artifact/com.pgvector/pgvector
```
If either lookup is unavailable, fall back to `org.postgresql:postgresql:42.7.4`
and `com.pgvector:pgvector:0.1.6` — both are known-good stable releases
compatible with Spring Boot 3.3.11 / Java 19.

- [ ] **Step 2: Add dependencies to `pom.xml`**

Add inside the existing `<dependencies>` block (after the `sqlite-jdbc`
dependency, `pom.xml:114-118`):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.4</version>
        </dependency>
        <dependency>
            <groupId>com.pgvector</groupId>
            <artifactId>pgvector</artifactId>
            <version>0.1.6</version>
        </dependency>
```

(Replace the two version numbers with whatever Step 1 found, if different.)
Leave the existing `org.xerial:sqlite-jdbc` dependency (`pom.xml:115-118`)
exactly as-is — it's still needed by the migration tool (Task 12).

- [ ] **Step 3: Add Postgres connection properties**

Add to `src/main/resources/application.properties`, after the `data.dir`
line at the top:

```properties
# Postgres connection for application storage + pgvector embeddings.
# This is the Supabase local dev stack's Postgres instance (already running,
# also serving agent-suite/soulman's own schemas) — not a dedicated
# digital-me database. digital-me gets its own schema (digitalme) inside
# the shared "postgres" database, matching how agent-suite/soulman do it.
# postgres.password resolves from the POSTGRES_PASSWORD env var, falling
# back to the literal "postgres" (Supabase CLI's own local-dev default) so
# nothing secret needs to be committed.
postgres.host=localhost
postgres.port=54322
postgres.database=postgres
postgres.user=postgres
postgres.password=${POSTGRES_PASSWORD:postgres}
postgres.schema=digitalme
```

- [ ] **Step 4: Verify dependency resolution**

Run:
```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q dependency:resolve
```
Expected: completes with no errors, no `Could not resolve dependencies`
message.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "build: add Postgres + pgvector dependencies and connection config"
```

---

## Task 2: Postgres schema DDL

**Files:**
- Modify: `src/main/resources/digital-me-db-1.sql` (full rewrite)
- Delete: `src/main/resources/digital-me-db-2.sql`
- Delete: `src/main/resources/digital-me-db-3.sql`
- Delete: `src/main/resources/digital-me-db-4.sql`
- Delete: `src/main/resources/digital-me-db-5.sql`
- Delete: `src/main/resources/digital-me-db-6.sql`

**Interfaces:**
- Produces: a single classpath resource `/digital-me-db-1.sql` that
  `DatabaseAdapter.init()` (Task 3) runs against an empty schema, creating
  all six tables plus the `vector` extension and the HNSW index.

- [ ] **Step 1: Delete the old SQLite-dialect migration scripts**

```bash
git rm src/main/resources/digital-me-db-2.sql src/main/resources/digital-me-db-3.sql src/main/resources/digital-me-db-4.sql src/main/resources/digital-me-db-5.sql src/main/resources/digital-me-db-6.sql
```

- [ ] **Step 2: Rewrite `digital-me-db-1.sql` as the full Postgres schema**

Replace the entire file content with:

```sql
-- This shared database (Supabase local dev stack, also serving agent-suite
-- and soulman) already has a conventional "extensions" schema for exactly
-- this purpose — install pgvector there once rather than into this
-- table's own "digitalme" schema, and reference the type schema-qualified
-- below so it resolves correctly regardless of this schema's search_path.
CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions;

CREATE TABLE APPLICATION_METADATA (
    KEY VARCHAR(1024) PRIMARY KEY NOT NULL,
    VALUE TEXT
);

CREATE TABLE TEXT_ENTRY (
    UUID VARCHAR(60) PRIMARY KEY NOT NULL,
    TIME VARCHAR(23) NOT NULL,
    NAME TEXT NOT NULL
);

CREATE TABLE TEXT_ENTRY_METADATA (
    TEXT_ENTRY_UUID VARCHAR(60) NOT NULL,
    KEY VARCHAR(1024) NOT NULL,
    VALUE TEXT,
    PRIMARY KEY (TEXT_ENTRY_UUID, KEY)
);

CREATE TABLE MCP_EMBEDDING (
    FILE_PATH   TEXT NOT NULL,
    CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
    SOURCE_URL  TEXT NOT NULL,
    CHUNK_TEXT  TEXT NOT NULL,
    EMBEDDING   extensions.VECTOR(768) NOT NULL,
    MODEL       TEXT NOT NULL,
    INDEXED_AT  TEXT NOT NULL,
    PRIMARY KEY (FILE_PATH, CHUNK_INDEX)
);
CREATE INDEX mcp_embedding_hnsw_idx ON MCP_EMBEDDING
    USING hnsw (EMBEDDING extensions.vector_cosine_ops);

CREATE TABLE SUMMARY_CACHE (
    SOURCE_URL TEXT NOT NULL PRIMARY KEY,
    SUMMARY    TEXT NOT NULL,
    CREATED_AT TEXT NOT NULL
);

CREATE TABLE ADD_CONTENT_QUEUE (
    UUID        VARCHAR(60) NOT NULL PRIMARY KEY,
    PAYLOAD     TEXT        NOT NULL,
    RECEIVED_AT TEXT        NOT NULL,
    ATTEMPTS    INTEGER     NOT NULL DEFAULT 0
);

INSERT INTO APPLICATION_METADATA (KEY, VALUE) VALUES ('database.version', '1');
```

`VECTOR(768)` matches `nomic-embed-text`'s output dimension — the only
supported embedding model today. Changing `ollama.embedding.model` to a
model with a different dimension later would require a new migration
script (`ALTER TABLE MCP_EMBEDDING ALTER COLUMN EMBEDDING TYPE vector(N)`).

This step alone can't be verified in isolation (nothing runs it yet) — it's
verified together with Task 3.

- [ ] **Step 3: Commit**

Commit together with Task 3 (see Task 3's commit step) since this file has
no effect until `DatabaseAdapter` can run it.

---

## Task 3: Rewrite `DatabaseAdapter` for pooled Postgres

**Files:**
- Modify: `src/main/java/com/breynisson/router/jdbc/DatabaseAdapter.java` (full rewrite)

**Interfaces:**
- Consumes: `digital-me-db-1.sql` (Task 2), `com.zaxxer.hikari.HikariDataSource`,
  `com.zaxxer.hikari.HikariConfig`, `com.pgvector.PGvector` (Task 1 deps).
- Produces:
  - `DatabaseAdapter.configure(String host, int port, String database, String user, String password, String schema)`
    — creates the schema if missing, then (re)builds the pooled `DataSource`.
  - `DatabaseAdapter.getConnection()` — returns a pooled `Connection` with
    the pgvector type registered, unchanged return type.
  - `DatabaseAdapter.selectList/selectOne/runPreparedStatement/runSql/init` —
    same signatures as today, now closing the borrowed connection in
    `finally`.
  - Removed: `setDefaultDatabasePath(String)`, `openSqliteConnection(String)`.

- [ ] **Step 1: Write the new `DatabaseAdapter.java`**

Full replacement:

```java
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
                if (sqlStatement.isEmpty() || "END".equalsIgnoreCase(sqlStatement) || sqlStatement.startsWith("--")) {
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
```

Note the two behavior changes from the SQLite version: `pg_tables`/
`current_schema()` replaces `SQLITE_MASTER`, and the table-existence check
now compares against the lowercase `"application_metadata"` (Postgres folds
unquoted identifiers to lowercase when creating them, so `pg_tables` reports
them lowercase even though the DDL and every DAO's SQL write it uppercase).

- [ ] **Step 2: Verify compilation**

Run:
```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q compile
```
Expected: compiles (existing callers of the removed `setDefaultDatabasePath`
will now fail to compile — that's expected; every such call site is fixed in
later tasks. If Task 3 is executed standalone, `mvn compile` (main sources
only) will succeed since only test code calls `setDefaultDatabasePath`; the
full `mvn test-compile`/`mvn test` will fail until Tasks 4-11 update the test
files — this is expected mid-migration and is resolved by Task 11).

- [ ] **Step 3: Manual smoke test against the real local Postgres instance**

Confirm the Supabase local dev stack's Postgres container is running
(`docker ps` should show `supabase_db_agent-suite` mapped to
`0.0.0.0:54322->5432/tcp`), then run:
```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q compile
```
followed by starting a `jshell` (or a tiny throwaway `main`) that calls:
```java
DatabaseAdapter.configure("localhost", 54322, "postgres", "postgres", "postgres", "digitalme");
DatabaseAdapter.init();
```
Expected: no exception; connecting with `psql` (or `docker exec
supabase_db_agent-suite psql -U postgres -d postgres`) afterward shows all
six tables created in the shared `postgres` database's new `digitalme`
schema, plus `SELECT * FROM digitalme.APPLICATION_METADATA` returning
`database.version = 1`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/breynisson/router/jdbc/DatabaseAdapter.java src/main/resources/digital-me-db-1.sql
git rm src/main/resources/digital-me-db-2.sql src/main/resources/digital-me-db-3.sql src/main/resources/digital-me-db-4.sql src/main/resources/digital-me-db-5.sql src/main/resources/digital-me-db-6.sql
git commit -m "feat: rewrite DatabaseAdapter for pooled Postgres, squash schema to Postgres dialect"
```

---

## Task 4: `PostgresTestSupport` + pilot conversion (`ApplicationMetadataDaoTest`)

**Files:**
- Create: `src/test/java/com/breynisson/router/jdbc/PostgresTestSupport.java`
- Modify: `src/test/java/com/breynisson/router/jdbc/ApplicationMetadataDaoTest.java`

**Interfaces:**
- Consumes: `DatabaseAdapter.configure(...)` (Task 3).
- Produces: `PostgresTestSupport.createIsolatedSchema(String namePrefix)` →
  `String` (the created schema name); `PostgresTestSupport.dropSchema(String schema)`.
  Every later test-file task uses these two methods.

- [ ] **Step 1: Write `PostgresTestSupport`**

```java
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
```

- [ ] **Step 2: Convert `ApplicationMetadataDaoTest`'s setup/teardown**

Read the current file first, then replace:

```java
    @TempDir
    static Path dbDir;

    @BeforeAll
    static void setUp() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("digital-me.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDown() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }
```

with:

```java
    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("applicationmetadatadao");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
    }
```

Remove the now-unused `import org.junit.jupiter.api.io.TempDir;` and
`import java.nio.file.Path;` if nothing else in the file uses them.

- [ ] **Step 3: Run the test**

Confirm your local Postgres instance is running, then:
```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=ApplicationMetadataDaoTest
```
Expected: `BUILD SUCCESS`, all tests pass. This is the pilot proving the
whole foundation (Hikari pool, schema creation, `init()`, `ON CONFLICT`-free
DAO) works end-to-end before converting every other test file.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/breynisson/router/jdbc/PostgresTestSupport.java src/test/java/com/breynisson/router/jdbc/ApplicationMetadataDaoTest.java
git commit -m "test: add PostgresTestSupport, convert ApplicationMetadataDaoTest to per-schema isolation"
```

---

## Task 5: Convert `TextEntryDaoTest`

**Files:**
- Modify: `src/test/java/com/breynisson/router/jdbc/TextEntryDaoTest.java`

**Interfaces:**
- Consumes: `PostgresTestSupport` (Task 4).

No `TextEntryDao` SQL changes — its `INSERT`/`UPDATE`/`SELECT` are already
portable Postgres syntax.

- [ ] **Step 1: Convert setup/teardown**

Replace:
```java
    @TempDir
    static Path dbDir;

    @BeforeAll
    static void setUp() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("digital-me.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDown() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }
```
with:
```java
    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("textentrydao");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
    }
```
Remove unused `@TempDir`/`Path` imports if nothing else in the file needs them.

- [ ] **Step 2: Run the test**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=TextEntryDaoTest
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/breynisson/router/jdbc/TextEntryDaoTest.java
git commit -m "test: convert TextEntryDaoTest to per-schema Postgres isolation"
```

---

## Task 6: `TextEntryMetadataDao` and `SummaryCacheDao` — `ON CONFLICT`

**Files:**
- Modify: `src/main/java/com/breynisson/router/jdbc/TextEntryMetadataDao.java`
- Modify: `src/main/java/com/breynisson/router/jdbc/SummaryCacheDao.java`
- Modify: `src/test/java/com/breynisson/router/jdbc/SummaryCacheDaoTest.java`

**Interfaces:**
- No signature changes — only the SQL text inside `upsert()` changes.

- [ ] **Step 1: Fix `TextEntryMetadataDao.upsert()`**

Replace:
```java
    public static void upsert(String uuid, String key, String value) {
        DatabaseAdapter.runPreparedStatement("INSERT OR REPLACE INTO " + TABLE_NAME + " (TEXT_ENTRY_UUID,KEY,VALUE) VALUES (?,?,?)", uuid, key, value);
    }
```
with:
```java
    public static void upsert(String uuid, String key, String value) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + TABLE_NAME + " (TEXT_ENTRY_UUID,KEY,VALUE) VALUES (?,?,?) "
              + "ON CONFLICT (TEXT_ENTRY_UUID, KEY) DO UPDATE SET VALUE = EXCLUDED.VALUE",
                uuid, key, value);
    }
```

- [ ] **Step 2: Fix `SummaryCacheDao.upsert()`**

Replace:
```java
    public static void upsert(String sourceUrl, String summary) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT OR REPLACE INTO " + TABLE + " (SOURCE_URL, SUMMARY, CREATED_AT) VALUES (?, ?, ?)",
                sourceUrl, summary, DatabaseAdapter.instantToTime(Instant.now()));
    }
```
with:
```java
    public static void upsert(String sourceUrl, String summary) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + TABLE + " (SOURCE_URL, SUMMARY, CREATED_AT) VALUES (?, ?, ?) "
              + "ON CONFLICT (SOURCE_URL) DO UPDATE SET SUMMARY = EXCLUDED.SUMMARY, CREATED_AT = EXCLUDED.CREATED_AT",
                sourceUrl, summary, DatabaseAdapter.instantToTime(Instant.now()));
    }
```

- [ ] **Step 3: Convert `SummaryCacheDaoTest`'s setup/teardown**

Same transformation as Task 5, Step 1, with prefix `"summarycachedao"`.

- [ ] **Step 4: Run the tests**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=SummaryCacheDaoTest
```
Expected: `BUILD SUCCESS`, including the upsert-replaces-existing-row test
(confirms `ON CONFLICT` actually replaces rather than erroring on the
duplicate key).

`TextEntryMetadataDao` has no dedicated test file today (verified: none
exists) — its `ON CONFLICT` correctness is covered indirectly by
`ClaudeSessionIndexerTest` (Task 10), which calls `upsert()` via
`ClaudeSessionIndexer`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/jdbc/TextEntryMetadataDao.java src/main/java/com/breynisson/router/jdbc/SummaryCacheDao.java src/test/java/com/breynisson/router/jdbc/SummaryCacheDaoTest.java
git commit -m "fix: translate INSERT OR REPLACE to Postgres ON CONFLICT in TextEntryMetadataDao and SummaryCacheDao"
```

---

## Task 7: `McpEmbedding` model + `McpEmbeddingDao` — vectors and `findSimilar`

**Files:**
- Modify: `src/main/java/com/breynisson/router/jdbc/model/McpEmbedding.java`
- Modify: `src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java`
- Modify: `src/test/java/com/breynisson/router/jdbc/McpEmbeddingDaoTest.java`

**Interfaces:**
- Consumes: `com.pgvector.PGvector` (Task 1).
- Produces:
  - `McpEmbedding.embedding` is now `float[]` (was `byte[]`).
  - `McpEmbeddingDao.findSimilar(float[] queryVector, float minScore, int topK)`
    → `List<McpEmbeddingDao.ScoredMatch>`.
  - `McpEmbeddingDao.ScoredMatch` record: `(String filePath, String sourceUrl, float score, String chunkText)`.
  - `McpEmbeddingDao.findAll()` is deleted.
  - `upsert`, `deleteByFilePath`, `deleteBySourceUrl`, `deleteByModelNot`,
    `findAllFilePaths`, `findFilePathsBySourceUrl`, `countIndexedFiles`,
    `countTotalChunks` — unchanged signatures.

- [ ] **Step 1: Update `McpEmbedding` model**

Replace the whole file:

```java
package com.breynisson.router.jdbc.model;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.pgvector.PGvector;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class McpEmbedding {

    public final String filePath;
    public final int chunkIndex;
    public final String sourceUrl;
    public final String chunkText;
    public final float[] embedding;
    public final String model;
    public final String indexedAt;

    public McpEmbedding(String filePath, int chunkIndex, String sourceUrl, String chunkText,
                         float[] embedding, String model, String indexedAt) {
        this.filePath = filePath;
        this.chunkIndex = chunkIndex;
        this.sourceUrl = sourceUrl;
        this.chunkText = chunkText;
        this.embedding = embedding;
        this.model = model;
        this.indexedAt = indexedAt;
    }

    public static class ResultSetTransform implements DatabaseAdapter.ResultSetTransform<McpEmbedding> {

        @Override
        public List<McpEmbedding> transform(ResultSet rset) throws SQLException {
            List<McpEmbedding> list = new ArrayList<>();
            while (rset.next()) {
                PGvector vector = (PGvector) rset.getObject(5); // EMBEDDING
                list.add(new McpEmbedding(
                        rset.getString(1),   // FILE_PATH
                        rset.getInt(2),       // CHUNK_INDEX
                        rset.getString(3),   // SOURCE_URL
                        rset.getString(4),   // CHUNK_TEXT
                        vector.toArray(),
                        null,                // MODEL not needed for search
                        null));              // INDEXED_AT not needed for search
            }
            return list;
        }
    }
}
```

- [ ] **Step 2: Rewrite `McpEmbeddingDao`**

Replace the whole file:

```java
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
        PGvector vector = new PGvector(queryVector);
        return DatabaseAdapter.selectList(
                "SELECT file_path, source_url, chunk_text, score FROM ("
              + "  SELECT DISTINCT ON (source_url) file_path, source_url, chunk_text, "
              + "         1 - (embedding <=> ?) AS score "
              + "  FROM " + TABLE + " "
              + "  ORDER BY source_url, embedding <=> ?"
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
```

- [ ] **Step 3: Convert `McpEmbeddingDaoTest`**

Read the current file first. Apply three changes:

1. Setup/teardown — same transformation as Task 5, Step 1, prefix
   `"mcpembeddingdao"`.
2. Replace the `embeddingBytes(float... values)` helper (returns `byte[]`
   via `ByteBuffer`) with a plain pass-through, since `McpEmbedding` now
   takes `float[]` directly:
   ```java
   private static float[] embeddingBytes(float... values) {
       return values;
   }
   ```
   (Keeping the helper's name and call sites unchanged minimizes the diff;
   it's now a trivial identity — leave every `embeddingBytes(1.0f, 2.0f)`
   call site as-is.)
3. In `findAllEmbeddingBytesRoundTrip()`, replace:
   ```java
   ByteBuffer buf = ByteBuffer.wrap(stored.embedding);
   assertArrayEquals(original, new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat()}, 0.0001f);
   ```
   with:
   ```java
   assertArrayEquals(original, stored.embedding, 0.0001f);
   ```
   Remove the now-unused `import java.nio.ByteBuffer;` if nothing else in
   the file uses it. Rename the test method to `findAllEmbeddingRoundTrips`
   for accuracy (optional but recommended — it no longer round-trips
   through bytes).

Also add three new tests exercising `findSimilar`:

```java
    @Test
    void findSimilarDedupsToBestScoringChunkPerSource() {
        String path = "/tmp/dao-test-similar-1.txt";
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://similar.com", "weak chunk",
                new float[]{0.0f, 1.0f}, "nomic-embed-text", "2026-01-01T00:00:00Z"));
        McpEmbeddingDao.upsert(new McpEmbedding(path, 1, "http://similar.com", "strong chunk",
                new float[]{1.0f, 0.0f}, "nomic-embed-text", "2026-01-01T00:00:00Z"));

        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(new float[]{1.0f, 0.0f}, 0f, 10);

        assertEquals(1, results.stream().filter(r -> r.sourceUrl().equals("http://similar.com")).count());
        assertEquals("strong chunk", results.stream()
                .filter(r -> r.sourceUrl().equals("http://similar.com")).findFirst().orElseThrow().chunkText());
        cleanup(path);
    }

    @Test
    void findSimilarAppliesMinScoreThreshold() {
        String path = "/tmp/dao-test-similar-2.txt";
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://orthogonal.com", "orthogonal chunk",
                new float[]{0.0f, 1.0f}, "nomic-embed-text", "2026-01-01T00:00:00Z"));

        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(new float[]{1.0f, 0.0f}, 0.5f, 10);

        assertTrue(results.stream().noneMatch(r -> r.sourceUrl().equals("http://orthogonal.com")));
        cleanup(path);
    }

    @Test
    void findSimilarLimitsToTopK() {
        String pathA = "/tmp/dao-test-similar-3-a.txt";
        String pathB = "/tmp/dao-test-similar-3-b.txt";
        McpEmbeddingDao.upsert(new McpEmbedding(pathA, 0, "http://topk-a.com", "chunk a",
                new float[]{1.0f, 0.0f}, "nomic-embed-text", "2026-01-01T00:00:00Z"));
        McpEmbeddingDao.upsert(new McpEmbedding(pathB, 0, "http://topk-b.com", "chunk b",
                new float[]{1.0f, 0.0f}, "nomic-embed-text", "2026-01-01T00:00:00Z"));

        List<McpEmbeddingDao.ScoredMatch> results = McpEmbeddingDao.findSimilar(new float[]{1.0f, 0.0f}, 0f, 1);

        assertEquals(1, results.size());
        cleanup(pathA);
        cleanup(pathB);
    }
```

Note: unlike `EmbeddingIndex.findSimilar` (which normalizes vectors before
storing/querying so cosine similarity and dot product coincide), these DAO
tests pass already-orthonormal-ish vectors (`{1,0}`/`{0,1}`) directly, so
`1 - cosine_distance` produces the same intuitive scores without needing a
separate normalization step in the test.

- [ ] **Step 4: Run the tests**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=McpEmbeddingDaoTest
```
Expected: `BUILD SUCCESS`, all tests including the three new `findSimilar`
tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/jdbc/model/McpEmbedding.java src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java src/test/java/com/breynisson/router/jdbc/McpEmbeddingDaoTest.java
git commit -m "feat: store MCP_EMBEDDING as native pgvector, add McpEmbeddingDao.findSimilar"
```

---

## Task 8: `EmbeddingIndex` — delete the in-memory cache

**Files:**
- Modify: `src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java`
- Modify: `src/test/java/com/breynisson/router/mcp/EmbeddingIndexTest.java`

**Interfaces:**
- Consumes: `McpEmbeddingDao.findSimilar(float[], float, int)` (Task 7).
- Produces: `EmbeddingIndex.findSimilar(String query, int topK)` →
  `List<EmbeddingIndex.ScoredResult>` — same public signature and
  `ScoredResult` shape as before, so `SemanticSearch` and its test need no
  changes.

- [ ] **Step 1: Rewrite `EmbeddingIndex`**

Replace the whole file:

```java
package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Indexes and queries dense vector embeddings for files in mcp-resources/.
 * Documents are split into chunks (see {@link Chunker}); each chunk gets its own row in the
 * MCP_EMBEDDING Postgres table (pgvector column, HNSW-indexed). Falls back gracefully when
 * the EmbeddingClient (Ollama) is unavailable.
 */
@Component
public class EmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);
    private static final String DEFAULT_MODEL = "nomic-embed-text";

    private final EmbeddingClient embeddingClient;
    private final Path mcpResourcesDir;
    private final String model;
    private final String documentPrefix;
    private final String queryPrefix;
    private final float minScore;

    @Autowired
    public EmbeddingIndex(
            EmbeddingClient embeddingClient,
            @Value("${data.dir:.}") String dataDir,
            @Value("${ollama.embedding.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${ollama.embedding.document-prefix:search_document:}") String documentPrefix,
            @Value("${ollama.embedding.query-prefix:search_query:}") String queryPrefix,
            @Value("${semantic-search.min-score:0.5}") float minScore) {
        this.embeddingClient = embeddingClient;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
        this.model = model;
        this.documentPrefix = documentPrefix;
        this.queryPrefix = queryPrefix;
        this.minScore = minScore;
    }

    /** Convenience constructor for tests: default model, no task prefixes, no score threshold. */
    public EmbeddingIndex(EmbeddingClient embeddingClient, String dataDir) {
        this(embeddingClient, dataDir, DEFAULT_MODEL, "", "", 0f);
    }

    /** Indexes any mcp-resources files not yet in the embedding table. Runs async at startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void indexAllOnStartup() {
        Thread t = new Thread(this::indexAll, "embedding-indexer");
        t.setDaemon(true);
        t.start();
    }

    void indexAll() {
        try {
            if (!Files.isDirectory(mcpResourcesDir)) return;
            Set<String> diskPaths = listFilePaths();
            reconcileStaleFiles(diskPaths);
            McpEmbeddingDao.deleteByModelNot(model);
            Set<String> indexed = McpEmbeddingDao.findAllFilePaths();
            for (String path : diskPaths) {
                if (!indexed.contains(path)) indexFile(Paths.get(path));
            }
        } catch (Exception e) {
            log.warn("Error during startup embedding indexing", e);
        }
    }

    /** Counts files currently on disk under mcp-resources/. Returns 0 if the directory doesn't exist. */
    public int countFilesOnDisk() {
        try {
            if (!Files.isDirectory(mcpResourcesDir)) return 0;
            return listFilePaths().size();
        } catch (IOException e) {
            log.warn("Could not count files in {}", mcpResourcesDir, e);
            return 0;
        }
    }

    private Set<String> listFilePaths() throws IOException {
        Set<String> paths = new HashSet<>();
        try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> paths.add(f.toAbsolutePath().toString()));
        }
        return paths;
    }

    private void reconcileStaleFiles(Set<String> diskPaths) {
        for (String dbPath : McpEmbeddingDao.findAllFilePaths()) {
            if (!diskPaths.contains(dbPath)) {
                McpEmbeddingDao.deleteByFilePath(dbPath);
            }
        }
    }

    /** Generates and stores embeddings for the given file, one row per chunk. No-ops if Ollama is unavailable. */
    public void indexFile(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String sourceUrl = ResourceReceiver.firstLine(raw);
            int nl = raw.indexOf('\n');
            String body = nl >= 0 ? raw.substring(nl + 1) : raw;
            String filePath = file.toAbsolutePath().toString();
            String indexedAt = Instant.now().toString();

            List<com.breynisson.router.jdbc.model.McpEmbedding> rows = new ArrayList<>();
            List<String> chunks = Chunker.chunk(body);
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String toEmbed = documentPrefix.isEmpty() ? chunkText : documentPrefix + " " + chunkText;
                float[] embedding = embeddingClient.embed(toEmbed);
                if (embedding == null) return; // Ollama unavailable — retry the whole file next pass
                float[] normalized = normalize(embedding);
                rows.add(new com.breynisson.router.jdbc.model.McpEmbedding(
                        filePath, i, sourceUrl, chunkText, normalized, model, indexedAt));
            }
            for (com.breynisson.router.jdbc.model.McpEmbedding row : rows) {
                McpEmbeddingDao.upsert(row);
            }
            log.debug("Indexed {} chunk(s) for {}", rows.size(), file.getFileName());
        } catch (Exception e) {
            log.warn("Error indexing embedding for {}", file, e);
        }
    }

    /**
     * Embeds the query and returns the top-K most similar files by cosine similarity,
     * deduplicated to each source URL's single best-scoring chunk. Returns an empty list if
     * Ollama is unavailable or no embeddings are stored.
     */
    public List<ScoredResult> findSimilar(String query, int topK) {
        String prefixedQuery = queryPrefix.isEmpty() ? query : queryPrefix + " " + query;
        float[] rawQueryEmbedding = embeddingClient.embed(prefixedQuery);
        if (rawQueryEmbedding == null) return List.of();
        float[] queryVector = normalize(rawQueryEmbedding);
        try {
            return McpEmbeddingDao.findSimilar(queryVector, minScore, topK).stream()
                    .map(m -> new ScoredResult(m.filePath(), m.sourceUrl(), m.score(), m.chunkText()))
                    .toList();
        } catch (Exception e) {
            log.warn("Embedding search failed", e);
            return List.of();
        }
    }

    public record ScoredResult(String filePath, String sourceUrl, float score, String chunkText) {}

    private static float[] normalize(float[] v) {
        double magnitude = 0;
        for (float f : v) magnitude += f * (double) f;
        magnitude = Math.sqrt(magnitude);
        if (magnitude == 0) return v.clone();
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / magnitude);
        return out;
    }
}
```

- [ ] **Step 2: Convert `EmbeddingIndexTest`**

Read the current file first. Apply two changes:

1. Setup/teardown — same transformation as Task 5, Step 1, prefix
   `"embeddingindex"`.
2. In `indexFileChunksLongContentIntoMultipleEmbedCalls()`, replace:
   ```java
   long storedRows = McpEmbeddingDao.findAll().stream()
           .filter(e -> e.filePath.equals(file.toAbsolutePath().toString())).count();
   assertEquals(embedded.size(), storedRows);
   ```
   with:
   ```java
   assertEquals(embedded.size(), McpEmbeddingDao.countTotalChunks());
   ```
   (`findAll()` no longer exists; since every other test in this class
   cleans up its own rows via `cleanup()` immediately after running, and
   JUnit 5 runs test methods within a class sequentially by default, the
   table is empty of other tests' rows by the time this one runs, so a
   global chunk count is equivalent to a per-file count here.)

All other assertions in this file (`findSimilar`, `ScoredResult` fields)
need no changes — `EmbeddingIndex`'s public API is unchanged.

- [ ] **Step 3: Run the tests**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=EmbeddingIndexTest
```
Expected: `BUILD SUCCESS`, all 14 existing tests pass — including
`findSimilarDedupsToBestChunkPerFile`, `findSimilarDedupsAcrossDifferentFilesWithSameSourceUrl`,
and `findSimilarAppliesScoreThreshold`, now exercised against real Postgres
+ pgvector instead of the in-memory cache.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java src/test/java/com/breynisson/router/mcp/EmbeddingIndexTest.java
git commit -m "feat: delete EmbeddingIndex's in-memory vector cache, push similarity search into Postgres"
```

---

## Task 9: Convert `AddContentQueueDaoTest`

**Files:**
- Modify: `src/test/java/com/breynisson/router/jdbc/AddContentQueueDaoTest.java`

No `AddContentQueueDao` SQL changes needed — already portable.

- [ ] **Step 1: Convert setup/teardown**

Same transformation as Task 5, Step 1, prefix `"addcontentqueuedao"`.

- [ ] **Step 2: Run the test**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=AddContentQueueDaoTest
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/breynisson/router/jdbc/AddContentQueueDaoTest.java
git commit -m "test: convert AddContentQueueDaoTest to per-schema Postgres isolation"
```

---

## Task 10: `AppConfig` startup wiring + remaining DB-touching test files

**Files:**
- Modify: `src/main/java/com/breynisson/router/AppConfig.java`
- Modify: `src/test/java/com/breynisson/router/ClaudeSessionIndexerTest.java`
- Modify: `src/test/java/com/breynisson/router/AddContentQueueProcessorTest.java`
- Modify: `src/test/java/com/breynisson/router/FileChangeWatcherTest.java`
- Modify: `src/test/java/com/breynisson/router/ui/IndexPageTest.java`
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`
- Modify: `src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java`
- Modify: `src/test/java/com/breynisson/router/mcp/ResourceReceiverTest.java`
- Modify: `src/test/java/com/breynisson/router/SpringBootApplicationTest.java`

**Interfaces:**
- Consumes: `DatabaseAdapter.configure(host, port, database, user, password, schema)`
  (Task 3), `postgres.schema` config key (added in Task 1, default `digitalme`).

- [ ] **Step 1: Rewrite `AppConfig`'s constructor**

Replace:
```java
    public AppConfig(@Value("${data.dir:.}") String dataDir) {
        this.dataDir = dataDir;
        DatabaseAdapter.setDefaultDatabasePath(dataDir + "/digital-me.db");
        LuceneIndex.setIndexPath(dataDir + "/lucene-index");
        DatabaseAdapter.init();
    }
```
with:
```java
    public AppConfig(
            @Value("${data.dir:.}") String dataDir,
            @Value("${postgres.host:localhost}") String postgresHost,
            @Value("${postgres.port:54322}") int postgresPort,
            @Value("${postgres.database:postgres}") String postgresDatabase,
            @Value("${postgres.user:postgres}") String postgresUser,
            @Value("${postgres.password:postgres}") String postgresPassword,
            @Value("${postgres.schema:digitalme}") String postgresSchema) {
        this.dataDir = dataDir;
        DatabaseAdapter.configure(postgresHost, postgresPort, postgresDatabase, postgresUser, postgresPassword, postgresSchema);
        LuceneIndex.setIndexPath(dataDir + "/lucene-index");
        DatabaseAdapter.init();
    }
```
(The `@Value` default for `postgres.password` is intentionally the plain
literal `postgres`, not the nested `${POSTGRES_PASSWORD:postgres}` — that
env-var resolution already happened once when Spring resolved the
`postgres.password` property from `application.properties`, which nests
the placeholder itself. If `application.properties` isn't on the classpath
at all — never true for the real app, but worth knowing — this default
covers it.)

- [ ] **Step 2: Convert the six identical-pattern test files**

For each of the following files, apply this exact transformation:

Replace:
```java
    @TempDir
    static Path dbDir;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }
```
with:
```java
    static String schema;

    @BeforeAll
    static void setUpDatabase() {
        schema = PostgresTestSupport.createIsolatedSchema("<prefix>");
    }

    @AfterAll
    static void tearDownDatabase() {
        PostgresTestSupport.dropSchema(schema);
    }
```
(using `import com.breynisson.router.jdbc.PostgresTestSupport;`), and remove
the now-unused `static Path dbDir;` `@TempDir` field and its imports if
nothing else in the file uses `@TempDir`/`Path`:

| File | `<prefix>` |
|---|---|
| `ClaudeSessionIndexerTest.java` | `claudesessionindexer` |
| `AddContentQueueProcessorTest.java` | `addcontentqueueprocessor` |
| `FileChangeWatcherTest.java` | `filechangewatcher` |
| `ui/IndexPageTest.java` | `indexpage` |
| `digitalme/DefaultDigitalMeStorageTest.java` | `defaultdigitalmestorage` |
| `digitalme/SemanticSearchTest.java` | `semanticsearch` |

Each of these files also has other `@TempDir` fields (e.g. `dataDir`,
`indexDir`) that are unrelated to the database — those stay exactly as-is;
only the `dbDir`/`setDefaultDatabasePath` pair is replaced.

- [ ] **Step 3: Convert `ResourceReceiverTest`**

Same transformation as Step 2 with prefix `resourcereceiver`, plus update
its `embeddingBytes()` helper (used to build a `McpEmbedding` for direct
`McpEmbeddingDao.upsert()` calls in two tests). Replace:
```java
    private static byte[] embeddingBytes() {
        ByteBuffer buf = ByteBuffer.allocate(Float.BYTES);
        buf.putFloat(1.0f);
        return buf.array();
    }
```
with:
```java
    private static float[] embeddingBytes() {
        return new float[]{1.0f};
    }
```
Remove the now-unused `import java.nio.ByteBuffer;` if nothing else in the
file uses it.

- [ ] **Step 4: Convert `SpringBootApplicationTest`**

This one is different: it's a full `@SpringBootTest`, so `AppConfig`'s
constructor (Step 1) runs during Spring context startup and reads
`postgres.*` properties from the test's dynamic property registry, not
from `PostgresTestSupport` directly. Replace:
```java
	@AfterAll
	static void tearDownDatabase() {
		// AppConfig points DatabaseAdapter at data.dir/digital-me.db on context startup; without
		// closing it here, the open SQLite connection blocks JUnit from deleting the static
		// @TempDir on Windows.
		DatabaseAdapter.setDefaultDatabasePath(null);
	}
```
with:
```java
	private static String schema;

	@AfterAll
	static void tearDownDatabase() {
		PostgresTestSupport.dropSchema(schema);
	}
```
And extend the existing `@DynamicPropertySource` method to allocate and
register a fresh schema *before* the Spring context builds `AppConfig`:
```java
	@DynamicPropertySource
	static void overrideProperties(DynamicPropertyRegistry registry) throws URISyntaxException {
		// ClaudeSessionIndexer's @Scheduled job runs as part of this full context. Left pointed at
		// the real ~/.claude/projects, it would scan and re-embed the user's actual session history
		// (hundreds of files) on every test run instead of a fixed, fast, deterministic dataset, and
		// would write actual transcripts into a shared, never-cleaned-up directory on disk.
		registry.add("data.dir", () -> dataDir.toString());
		Path fixtureDir = Paths.get(SpringBootApplicationTest.class.getClassLoader()
				.getResource("claude-projects-fixture").toURI());
		registry.add("claude.projects.dir", fixtureDir::toString);

		// Isolate this full-context test's Postgres schema from real dev data and from every
		// other test class, the same way data.dir isolates its file-based state above.
		schema = "springbootapplicationtest_" + java.util.UUID.randomUUID().toString().replace("-", "");
		registry.add("postgres.schema", () -> schema);
	}
```
Add `import com.breynisson.router.jdbc.PostgresTestSupport;`. Remove the
now-unused `import com.breynisson.router.jdbc.DatabaseAdapter;` if nothing
else in the file references `DatabaseAdapter` directly.

- [ ] **Step 5: Run the full test suite**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test
```
Expected: `BUILD SUCCESS`, every test passes. This is the first point the
entire suite runs green against Postgres end-to-end.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/breynisson/router/AppConfig.java src/test/java/com/breynisson/router/ClaudeSessionIndexerTest.java src/test/java/com/breynisson/router/AddContentQueueProcessorTest.java src/test/java/com/breynisson/router/FileChangeWatcherTest.java src/test/java/com/breynisson/router/ui/IndexPageTest.java src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java src/test/java/com/breynisson/router/mcp/ResourceReceiverTest.java src/test/java/com/breynisson/router/SpringBootApplicationTest.java
git commit -m "feat: wire AppConfig to configure Postgres on startup; convert remaining tests to per-schema isolation"
```

---

## Task 11: `SqliteToPostgresMigrator` — one-time migration tool

**Files:**
- Create: `src/main/java/com/breynisson/router/migration/SqliteToPostgresMigrator.java`
- Create: `src/test/java/com/breynisson/router/migration/SqliteToPostgresMigratorTest.java`
- Modify: `src/main/java/com/breynisson/router/SpringBootApplication.java`

**Interfaces:**
- Produces: a `CommandLineRunner` bean active only when
  `digitalme.migrate-sqlite-path` is set; `SqliteToPostgresMigrator.migrate()`
  (package-visible, no `System.exit`) is the testable entry point.

- [ ] **Step 1: Write the failing test**

```java
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

        var matches = McpEmbeddingDao.findSimilar(new float[]{0.6f, 0.8f}, 0f, 10);
        assertEquals(1, matches.size());
        assertEquals("http://fixture.com", matches.get(0).sourceUrl());
        assertEquals(1.0f, matches.get(0).score(), 0.001f,
                "migrated vector queried against itself should score a perfect cosine match");

        AddContentQueueDao.delete("queue-uuid");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=SqliteToPostgresMigratorTest
```
Expected: FAIL — compile error, `SqliteToPostgresMigrator` doesn't exist yet.

- [ ] **Step 3: Write `SqliteToPostgresMigrator`**

```java
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
                float[] vector = unpackFloats(rs.getBytes(5));
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
}
```

- [ ] **Step 4: Run the test again to verify it passes**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=SqliteToPostgresMigratorTest
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Make the migration command skip starting the web server**

Replace `SpringBootApplication.java`'s `main` method:
```java
    public static void main(String[] args) {
        SpringApplication.run(SpringBootApplication.class, args);
    }
```
with:
```java
    public static void main(String[] args) {
        org.springframework.boot.builder.SpringApplicationBuilder builder =
                new org.springframework.boot.builder.SpringApplicationBuilder(SpringBootApplication.class);
        boolean migrating = java.util.Arrays.stream(args)
                .anyMatch(a -> a.startsWith("--digitalme.migrate-sqlite-path"));
        if (migrating) {
            builder.web(org.springframework.boot.WebApplicationType.NONE);
        }
        builder.run(args);
    }
```
(Fully-qualifying these three one-off usages avoids adding three new
top-level imports for a method that uses each only once — matches this
file's existing minimal-import style.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/breynisson/router/migration/SqliteToPostgresMigrator.java src/test/java/com/breynisson/router/migration/SqliteToPostgresMigratorTest.java src/main/java/com/breynisson/router/SpringBootApplication.java
git commit -m "feat: add one-time SQLite-to-Postgres migration tool"
```

---

## Task 12: Documentation updates

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/architecture.md`
- Modify: `docs/testing.md`
- Modify: `docs/tooling.md`

- [ ] **Step 1: Update `CLAUDE.md`'s tech-stack table**

Replace the `Database` row:
```
| Database | SQLite via `sqlite-jdbc` |
```
with:
```
| Database | PostgreSQL + `pgvector` (via `org.postgresql:postgresql`, pooled with HikariCP) |
```

- [ ] **Step 2: Update `docs/architecture.md`**

Replace the "Database schema" section's SQLite DDL block with the Postgres
DDL from Task 2, Step 2 (the full `digital-me-db-1.sql` content), and add a
note directly under it:

```
`VECTOR(768)` is fixed to `nomic-embed-text`'s output dimension — the only
supported embedding model. Changing `ollama.embedding.model` to a
different-dimension model requires a new migration (`ALTER TABLE
MCP_EMBEDDING ALTER COLUMN EMBEDDING TYPE vector(N)`), the same way
`deleteByModelNot()` already discards and re-embeds on any model change
today. `EMBEDDING` is queried via pgvector's `<=>` cosine-distance operator
against an HNSW index (`mcp_embedding_hnsw_idx`), not loaded into
application memory.
```

Update the `DatabaseAdapter` bullet under "Key subsystems" to describe the
pooled Postgres `DataSource` (HikariCP) instead of the single SQLite
connection, and the `configure(host, port, database, user, password, schema)`
method replacing `setDefaultDatabasePath`.

Update the `EmbeddingIndex` bullet to remove references to the in-memory
cache and describe `findSimilar()` delegating straight to
`McpEmbeddingDao.findSimilar()`.

Update the `McpEmbeddingDao` bullet: `findAll()` is gone; add
`findSimilar(float[] queryVector, float minScore, int topK)` doing the
per-source dedup + threshold + top-K directly in SQL via `DISTINCT ON`.

- [ ] **Step 3: Update `docs/testing.md`**

Replace the SQLite `@TempDir` convention bullets with:

```
## Postgres tests

- DB tests connect to the Supabase local dev stack's Postgres instance
  (`localhost:54322`, database `postgres`, default `postgres`/`postgres`
  credentials — override via the `POSTGRES_PASSWORD` env var if yours
  differs) — the same instance `agent-suite` and `soulman` use, each in
  their own schema. This is a prerequisite for running `mvn test`, the same
  way some tests require Ollama running.
- Use `PostgresTestSupport.createIsolatedSchema("<prefix>")` in `@BeforeAll`
  to get a fresh, isolated schema per test class (replaces the old
  per-class SQLite `@TempDir` file); `PostgresTestSupport.dropSchema(schema)`
  in `@AfterAll` to clean up.
- `pgvector` is installed once into this shared instance's `extensions`
  schema (`CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions`, run by
  `DatabaseAdapter.init()`) — the extension's shared library must already
  be present on the Postgres server (see `docs/tooling.md`); it was
  confirmed available (v0.8.0) on this instance.
```

- [ ] **Step 4: Update `docs/tooling.md`**

Add a new section:

```
## Postgres

digital-me uses the Supabase local dev stack's Postgres instance (also
serving `agent-suite` and `soulman`), not a dedicated install of its own —
it gets its own schema (`digitalme`) inside that shared `postgres`
database. That instance already has `pgvector` 0.8.0 available (HNSW index
support requires v0.5.0+); `DatabaseAdapter.init()` enables it once via
`CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions`.

Default connection: `localhost:54322`, database `postgres`, schema
`digitalme`, user `postgres`. Password resolves from the
`POSTGRES_PASSWORD` environment variable, falling back to the literal
`postgres` (Supabase CLI's own local-dev default) otherwise.

## One-time SQLite-to-Postgres migration

Run once, after upgrading to this version and before relying on it day to
day:

```
java -jar target/digital-me-0.1.jar --digitalme.migrate-sqlite-path=C:\Users\Lenovo\DigitalMe\digital-me.db
```

This copies every row out of the old SQLite file into the already-schema'd
Postgres database and exits — it does not start the web server, and never
modifies or deletes the old SQLite file. Not safe to re-run against a
non-empty Postgres database (not idempotent).
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/architecture.md docs/testing.md docs/tooling.md
git commit -m "docs: update architecture, testing, and tooling docs for Postgres + pgvector"
```

---

## Task 13: Final full-suite verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test
```
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 2: Run checkstyle**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q checkstyle:check
```
Expected: exits 0, no violations.

- [ ] **Step 3: Run a full package build**

```
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q package
```
Expected: `BUILD SUCCESS`, produces `target/digital-me-0.1.jar`.

- [ ] **Step 4: Manual smoke test of the running app**

Start the packaged jar against the real local Postgres instance (with
`digital-me-dev/` as the working directory, per project convention), confirm
`/health/index` responds, and run a keyword + semantic search from the
frontend to confirm results still come back correctly with the new
pgvector-backed search path.

No commit for this task — it's pure verification of everything already
committed in Tasks 1-12.
