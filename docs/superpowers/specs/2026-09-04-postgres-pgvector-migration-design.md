# Migrate application storage from SQLite to Postgres + pgvector

## Problem

All application data lives in a single SQLite file
(`C:\Users\Lenovo\DigitalMe\digital-me.db`), including `MCP_EMBEDDING`, whose
`EMBEDDING` column holds one packed-float BLOB per chunk. `EmbeddingIndex`
loads every row of `MCP_EMBEDDING` into an in-memory `ConcurrentHashMap`
(`loadCacheFromDatabase()`) at startup and after every write, and scores
every cached vector by brute-force dot product on every `findSimilar()` call.
This keeps the JVM's heap usage tied to the total number of indexed chunks,
which only grows over time as more content gets indexed — it's already too
large and has no ceiling.

Postgres with the `pgvector` extension stores vectors out-of-process and can
answer nearest-neighbor queries (optionally via an approximate HNSW index)
without ever materializing the whole table in application memory. This
migration moves the embedding table (and, for consistency, every other
SQLite table) to a local Postgres instance, and replaces the in-memory
scoring path with a SQL query.

## Scope

All application data moves: `APPLICATION_METADATA`, `TEXT_ENTRY`,
`TEXT_ENTRY_METADATA`, `MCP_EMBEDDING`, `SUMMARY_CACHE`, `ADD_CONTENT_QUEUE`.
SQLite is retired from the running application entirely — `DatabaseAdapter`
no longer knows how to open a SQLite file. The `sqlite-jdbc` dependency stays
in the pom solely so the one-time migration tool (below) can read the old
database file directly; nothing else in the app uses it after migration.

`LuceneIndex` (full-text keyword search) is unrelated to this change and is
untouched — it already lives outside SQLite as its own on-disk index.

## Design

### Dependencies (`pom.xml`)

- `org.postgresql:postgresql` — JDBC driver.
- `com.pgvector:pgvector` — small helper library providing a `PGvector` type
  that binds a Java `float[]` to Postgres's `vector` column type and
  registers it on a JDBC connection, so `PreparedStatement.setObject(...)`
  works without hand-rolled encoding.
- `spring-boot-starter-jdbc` — not currently a dependency; adding it brings
  in HikariCP (Spring Boot 3.3.11's default connection pool) and Spring's
  transaction infrastructure, so `HikariDataSource` is available without a
  separate explicit HikariCP dependency.
- `org.xerial:sqlite-jdbc` stays, used only by the migration tool.

### Configuration (`application.properties`)

The actual local Postgres instance is not a dedicated `digital-me` install —
it's the Supabase local dev stack's Postgres container (already running,
serving the `agent-suite` and `soulman` projects via their own schemas —
`projects_dev`/`projects_prod`/`projects_test` and `memory_dev`/`memory_prod`
respectively, inside the single shared `postgres` database on Supabase's
default port `54322`). digital-me follows the same convention: its own
dedicated schema (`digitalme`) inside that shared database, not a separate
database of its own.

```properties
postgres.host=localhost
postgres.port=54322
postgres.database=postgres
postgres.user=postgres
postgres.password=${POSTGRES_PASSWORD:postgres}
postgres.schema=digitalme
```

`postgres.password` resolves from the `POSTGRES_PASSWORD` environment
variable when set, falling back to the literal `postgres` (Supabase CLI's
own local-dev default, confirmed working) otherwise — so nothing secret
needs to be committed. Since the app already binds to `127.0.0.1` only and
this is a documented single-user local tool, a plaintext local default is
an accepted trade-off, consistent with how Ollama's `localhost:11434` is
treated today.

pgvector 0.8.0 is available on this instance (confirmed via
`pg_available_extensions`) but not yet enabled. This shared database
already has a conventional `extensions` schema (visible via `\dn`, used by
Supabase's own extensions) — `CREATE EXTENSION IF NOT EXISTS vector SCHEMA
extensions` installs it there once, and the schema DDL (below) references
the type as `extensions.vector(...)` / `extensions.vector_cosine_ops`
explicitly rather than relying on `digitalme` schema's search_path to
include `extensions`.

### `DatabaseAdapter` — pooled Postgres connection behind the same facade

`DatabaseAdapter` keeps its existing static API
(`selectList`/`selectOne`/`runPreparedStatement`/`runSql`/`init`/`getConnection`)
so every DAO's calling code is unchanged. Internally:

- The single long-lived SQLite `Connection` field is replaced by a static
  `HikariDataSource`, built once from the `postgres.*` properties (read via
  a new `DatabaseAdapter.configure(host, port, database, user, password, schema)`
  called from `AppConfig` at startup, mirroring how `setDefaultDatabasePath()`
  configures SQLite today). `configure()` itself runs `CREATE SCHEMA IF NOT
  EXISTS <schema>` against a bootstrap connection before building the pool,
  so the same method handles both the app's own `digitalme` schema and each
  test class's freshly-generated one (see Testing).
- `getConnection()` now returns `dataSource.getConnection()` — a pooled
  connection, not a shared singleton.
- **Important behavior change:** because connections are now pooled, every
  method that borrows one (`selectList`, `selectOne`'s delegation,
  `runPreparedStatement`, `runSql`) must `close()` it in its `finally` block
  to return it to the pool. Today's `safeClose(rset, pstmt)` calls stay, and
  `safeClose(connection)` is added alongside them. (Today's single-Connection
  design deliberately never closes it; that would now exhaust the pool.)
- `setDefaultDatabasePath(String)` is replaced entirely by `configure(...)`
  above — tests call it with a freshly-generated schema name instead of a
  temp file path (see Testing).
- `openSqliteConnection` and the `org.sqlite.JDBC` class-loading are removed
  from `DatabaseAdapter`.

### Schema DDL — Postgres-dialect migration scripts

`DatabaseAdapter.init()`'s existing mechanism (numbered
`digital-me-db-N.sql` resources tracked via
`APPLICATION_METADATA.database.version`) is reused unchanged — it already
just runs whatever SQL text is in each file, split on `;`. The six existing
scripts are translated to a fresh Postgres-dialect set (a clean numbered
sequence starting at 1, since a newly-created, empty `digitalme` schema has
no prior version to reconcile against):

```sql
-- digital-me-db-1.sql
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

`VECTOR(768)` matches `nomic-embed-text`'s output dimension (the current,
and only supported, embedding model). **Accepted limitation:** if
`ollama.embedding.model` is ever changed to a model with a different output
dimension, the column width must be migrated (new `ALTER TABLE ... TYPE
vector(N)` script) — this is a fixed, not dynamic, dimension. This is no
worse than today, where `deleteByModelNot()` already discards all rows on a
model change and re-embeds from scratch.

All identifiers are unquoted uppercase in the DDL, matching every existing
DAO's SQL strings (`SELECT ... FROM MCP_EMBEDDING WHERE FILE_PATH = ?`).
Postgres folds unquoted identifiers to lowercase, and so does every
unquoted reference to them — so existing DAO SQL keeps working verbatim
without a single query needing case changes, exactly like it does against
SQLite today (which is fully case-insensitive for unquoted identifiers).

### `INSERT OR REPLACE` → `ON CONFLICT`

Three call sites use SQLite's `INSERT OR REPLACE`; each becomes Postgres's
`INSERT ... ON CONFLICT (pk) DO UPDATE SET ...`:

- `McpEmbeddingDao.upsert()` — conflict target `(FILE_PATH, CHUNK_INDEX)`.
- `TextEntryMetadataDao.upsert()` — conflict target `(TEXT_ENTRY_UUID, KEY)`.
- `SummaryCacheDao.upsert()` — conflict target `(SOURCE_URL)`.

Example (`McpEmbeddingDao`):

```java
DatabaseAdapter.runPreparedStatement(
    "INSERT INTO MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) "
  + "VALUES (?, ?, ?, ?, ?, ?, ?) "
  + "ON CONFLICT (FILE_PATH, CHUNK_INDEX) DO UPDATE SET "
  + "SOURCE_URL = EXCLUDED.SOURCE_URL, CHUNK_TEXT = EXCLUDED.CHUNK_TEXT, EMBEDDING = EXCLUDED.EMBEDDING, "
  + "MODEL = EXCLUDED.MODEL, INDEXED_AT = EXCLUDED.INDEXED_AT",
    embedding.filePath, embedding.chunkIndex, embedding.sourceUrl, embedding.chunkText,
    new PGvector(embedding.embedding), embedding.model, embedding.indexedAt);
```

### `McpEmbedding` model — `float[]` instead of `byte[]`

The `EMBEDDING` column is now a native vector type, not an opaque BLOB, so
`McpEmbedding.embedding` changes from `byte[]` to `float[]`, and its
`ResultSetTransform` reads it via `PGvector`'s result-set accessor (or
`((PgVector) rset.getObject(N)).toArray()`) instead of `rset.getBytes(N)`.
The manual `toBytes()`/`fromBytes()` big-endian packing helpers in
`EmbeddingIndex` are deleted — they existed only to serialize into a BLOB
column.

### `EmbeddingIndex` — delete the in-memory cache, push search into SQL

This is the core of the fix:

- **Deleted:** the `cache` field, `CacheKey`/`CachedEmbedding` records,
  `loadCacheFromDatabase()`, and the in-memory `dot()`-based scoring loop in
  `findSimilar()`.
- **`indexFile()`** unchanged in shape (still chunks, embeds, normalizes),
  except it no longer writes into a cache — it only calls
  `McpEmbeddingDao.upsert()`.
- **`findSimilar(query, topK)`** becomes:
  ```java
  public List<ScoredResult> findSimilar(String query, int topK) {
      String prefixedQuery = queryPrefix.isEmpty() ? query : queryPrefix + " " + query;
      float[] rawQueryEmbedding = embeddingClient.embed(prefixedQuery);
      if (rawQueryEmbedding == null) return List.of();
      float[] queryVector = normalize(rawQueryEmbedding);
      return McpEmbeddingDao.findSimilar(queryVector, minScore, topK);
  }
  ```
  `normalize()`/`dot()` static helpers stay (normalization still happens
  client-side before storing/querying; `dot()` is deleted since scoring now
  happens in SQL).
- **`McpEmbeddingDao.findSimilar(float[] queryVector, float minScore, int topK)`**
  (new) does the dedup-by-source + threshold + top-K in one query:
  ```sql
  SELECT file_path, source_url, chunk_text, score FROM (
      SELECT DISTINCT ON (source_url)
          file_path, source_url, chunk_text,
          1 - (embedding <=> ?) AS score
      FROM MCP_EMBEDDING
      ORDER BY source_url, embedding <=> ?
  ) best_per_source
  WHERE score >= ?
  ORDER BY score DESC
  LIMIT ?
  ```
  The query vector is bound twice (`PGvector`, once per `<=>` reference) plus
  `minScore` and `topK`. `<=>` is pgvector's cosine-distance operator;
  `1 - distance` reconstructs the cosine-similarity score the app already
  filters/sorts by today, so `ExclusionRules` and the snippet-building code
  downstream see identical `ScoredResult` shapes and semantics.
- **`indexAll()`** keeps its existing reconciliation shape
  (`reconcileStaleFiles`, `deleteByModelNot`, then indexing files not yet in
  the DB) but no longer calls `loadCacheFromDatabase()` — there's no cache to
  populate. `McpEmbeddingDao.findAll()` is deleted (nothing needs the full
  table in memory); `findAllFilePaths()` stays, used for reconciliation.
- `countFilesOnDisk()` and the `/health/index` endpoint are unaffected — they
  never touched the in-memory cache.

### HNSW index

`CREATE INDEX ... USING hnsw (EMBEDDING vector_cosine_ops)` (in the schema
DDL above) makes `findSimilar`'s `ORDER BY embedding <=> ?` use approximate
nearest-neighbor search instead of a sequential scan, keeping query latency
low as the table grows well beyond current scale. This trades a small,
usually negligible amount of recall for that scalability — an accepted
trade-off given the goal is fixing unbounded memory growth, not maximizing
recall at today's small scale (where exact search would also be fast).
Default HNSW build parameters (`m`, `ef_construction`) are left at pgvector's
defaults; nothing in this design calls for tuning them.

### Migration tool

A new `SqliteToPostgresMigrator` `CommandLineRunner` bean, active only when
a new property is supplied on the command line, e.g.:

```
java -jar digital-me-0.1.jar --digitalme.migrate-sqlite-path=C:\Users\Lenovo\DigitalMe\digital-me.db
```

Behavior:

1. Runs *after* normal Spring context startup, so `DatabaseAdapter` is
   already configured and `DatabaseAdapter.init()` has already created the
   Postgres schema (empty tables) via the DDL scripts above.
2. Opens the SQLite file at the given path directly via `org.sqlite.JDBC`
   (not through `DatabaseAdapter`, which no longer knows SQLite).
3. For each table except `APPLICATION_METADATA` (Postgres manages its own
   `database.version`, so this row is intentionally not copied):
   `TEXT_ENTRY`, `TEXT_ENTRY_METADATA`, `MCP_EMBEDDING`, `SUMMARY_CACHE`,
   `ADD_CONTENT_QUEUE` — `SELECT *` from SQLite, batch-insert into Postgres.
   `MCP_EMBEDDING.EMBEDDING` bytes are unpacked with the same big-endian
   `ByteBuffer` logic `EmbeddingIndex.fromBytes()` used to use (kept as a
   small private static helper in the migrator, since the production code no
   longer needs it) and wrapped in a `PGvector` for insertion.
4. Logs a per-table row count summary, then calls `System.exit(0)` instead of
   letting the web server start — this is a one-time, explicit command, never
   something that runs as a side effect of normal startup.
5. The old SQLite file is never modified or deleted — it's left on disk as a
   backup. Cutover is manual: stop the app, run the migration command once,
   spot-check row counts/search results, then run the app normally
   afterward (already pointed at Postgres via `application.properties`).

Re-running the migrator against a non-empty Postgres database is not
guarded against (no idempotency check) — this is a deliberate one-time tool
for a single-user cutover, not a repeatable sync; running it twice would
duplicate or conflict on primary keys (`ON CONFLICT` upserts are not used
here, since this is a fresh copy, not a merge).

## Testing

### `PostgresTestSupport` (new, replaces the `@TempDir` SQLite pattern)

```java
public final class PostgresTestSupport {
    public static String createIsolatedSchema(String namePrefix) {
        String schema = namePrefix + "_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseAdapter.configure("localhost", 54322, "postgres", "postgres", PASSWORD, schema);
        DatabaseAdapter.init();
        return schema;
    }

    public static void dropSchema(String schema) {
        // via a separate bootstrap connection, independent of DatabaseAdapter's pool
        // DROP SCHEMA IF EXISTS <schema> CASCADE
    }
}
```

Connects to the same local Postgres instance the app uses in development
(`postgres.*` defaults), each test class getting its own schema instead of
its own SQLite file — the same isolation guarantee, without Docker. This
requires the local Postgres instance (with `pgvector` installed) to be
running whenever `mvn test` runs; `docs/testing.md` documents this as a
prerequisite.

Existing DB test classes (`McpEmbeddingDaoTest`, `TextEntryDaoTest`,
`EmbeddingIndexTest`, `ApplicationMetadataDaoTest`, etc.) change their
`@BeforeAll`/`@AfterAll` from:

```java
@TempDir static Path dbDir;
@BeforeAll static void setUp() {
    DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
    DatabaseAdapter.init();
}
@AfterAll static void tearDown() { DatabaseAdapter.setDefaultDatabasePath(null); }
```

to:

```java
static String schema;
@BeforeAll static void setUp() { schema = PostgresTestSupport.createIsolatedSchema("mcpembeddingdao"); }
@AfterAll static void tearDown() { PostgresTestSupport.dropSchema(schema); }
```

`McpEmbeddingDaoTest`'s `embeddingBytes(float...)` helper and byte-array
round-trip assertions are simplified to plain `float[]`, since the DAO no
longer stores packed bytes.

### New tests

- **`SqliteToPostgresMigratorTest`**: seeds a small temporary SQLite file
  (via `org.sqlite.JDBC` directly, not `DatabaseAdapter`) with one or two
  rows per table, runs the migrator against an isolated Postgres schema, and
  asserts each table's rows landed correctly — including a
  `MCP_EMBEDDING` row's vector round-tripping to the same `float[]` values.
- **`McpEmbeddingDao.findSimilar()` tests**: verifies per-source dedup
  (multiple chunks from the same `SOURCE_URL` collapse to the best-scoring
  one), the `minScore` threshold, and `topK` limiting — replacing the
  equivalent assertions that used to live against `EmbeddingIndex`'s
  in-memory cache in `EmbeddingIndexTest`.

## Docs

- `CLAUDE.md`: tech-stack table row `Database | SQLite via sqlite-jdbc` →
  `Database | PostgreSQL + pgvector`.
- `docs/architecture.md`: rewrite the "Database schema" section for the
  Postgres DDL above (dropping the SQLite-specific block); update
  `DatabaseAdapter`, `McpEmbeddingDao`, and `EmbeddingIndex` subsystem notes
  to describe pooled connections and SQL-side similarity search instead of
  the in-memory cache; note the fixed `vector(768)` dimension constraint.
- `docs/testing.md`: replace the `@TempDir`-per-SQLite-file convention with
  `PostgresTestSupport`'s per-schema pattern, and note the local Postgres +
  `pgvector` prerequisite for running `mvn test`.
- `docs/tooling.md`: add a "Postgres" section covering local install +
  `CREATE EXTENSION vector`, and document the migration command
  (`--digitalme.migrate-sqlite-path=...`).

## Out of scope

- No dual-write or gradual rollout — this is a single-user local app; the
  cutover is stop-app / migrate-once / restart-pointed-at-Postgres.
- No removal of the old SQLite file — left on disk as a backup, untouched.
- No support for changing `ollama.embedding.model` to a different-dimension
  model as part of this migration — `VECTOR(768)` is fixed to
  `nomic-embed-text`; switching models later is a separate follow-up
  migration.
- No idempotency/re-run safety for the migration tool — it's a one-time
  command for a single cutover, not a repeatable sync.
- No connection-pool size tuning beyond Hikari's defaults — nothing in this
  design calls for it at this app's scale.
- No change to `LuceneIndex`, keyword search, or any non-database subsystem.
