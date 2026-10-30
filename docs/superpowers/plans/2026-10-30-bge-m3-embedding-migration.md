# bge-m3 Embedding Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move semantic search from `nomic-embed-text` (768-dim) to `bge-m3` (1024-dim) gradually and crash-safely, with all content ending up on bge-m3.

**Architecture:** A new `MCP_EMBEDDING_V2` table sits beside the old `MCP_EMBEDDING`, which gains a `MIGRATED_AT` column as the durable checkpoint. A background `EmbeddingMigrator` re-embeds each file's stored chunk text and commits the V2 rows plus the `MIGRATED_AT` marks in one transaction. New content is dual-written, and search queries both tables and merges by reciprocal rank fusion. Everything is gated by `embedding.v2.enabled`, so with the flag off the app behaves exactly as today.

**Tech Stack:** Java 19, Spring Boot 3.3.11, PostgreSQL + pgvector (HikariCP), JUnit 5, Ollama (`bge-m3`).

**Spec:** `docs/superpowers/specs/2026-10-30-bge-m3-embedding-migration-design.md`

## Global Constraints

- Build/test with the IntelliJ Maven: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<Class>` (never `package`; it runs npm). `mvn` is not on PATH.
- DB tests need the Supabase Postgres at `localhost:54322`; use `PostgresTestSupport.createIsolatedSchema("<prefix>")` in `@BeforeAll` and `dropSchema` in `@AfterAll`; clean up rows explicitly, delete before insert.
- Embedding test vectors must be zero-padded: 768 dims for `MCP_EMBEDDING`, **1024 dims for `MCP_EMBEDDING_V2`** (pgvector rejects other lengths).
- `DatabaseAdapter.runSql` splits SQL scripts on `;` - no semicolons inside SQL comments in `digital-me-db-3.sql`.
- Timestamps (`INDEXED_AT`, `MIGRATED_AT`) are ISO-8601 instant strings (`Instant.toString()`).
- bge-m3 uses no task prefixes: `embedding.v2.document-prefix` and `embedding.v2.query-prefix` default to empty. Where a prefix is set, text is `prefix + " " + text` (existing convention in `EmbeddingIndex`).
- Checkstyle rules (`checkstyle.xml`): no unused imports, no empty catch blocks, `equals` with constant on the left, one statement per line.
- Existing public signatures (`McpEmbeddingDao.upsert(McpEmbedding)`, `findSimilar(float[], float, int)`, `new EmbeddingIndex(client, dataDir)`, the Spring `EmbeddingIndex` constructor shape for existing tests, `IndexPage` constructor) keep working; existing tests must stay green.
- Work happens on branch `feature/bge-m3-embedding-migration` in its worktree. `git add` every new file. Do not push. Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- With `embedding.v2.enabled` unset/false there must be no behaviour change: no `MIGRATED_AT` filter, no dual-write, no migrator, no `migration` block in `/health/index`.

## Review Focus

- bge-m3 not pulled / Ollama down (embed returns null): the migrator must neither mark nor write anything and must back off, not spin (Task 4).
- A file replaced or deleted between reading its chunks and committing: no orphaned V2 rows, no stale marker (Task 2).
- A file re-indexed at the same path after being migrated: the old row's `MIGRATED_AT` must reset to NULL so it is migrated again (Task 1).
- Same `source_url` present in both tables (stale V2 rows after re-index): appears once in fused results (Task 3).
- bge-m3 scores are lower than nomic's: the V2 side must use its own `min-score`, not 0.5 (Task 3).

---

### Task 1: Schema, transaction helper, table-parametrised DAO

**Files:**
- Create: `src/main/resources/digital-me-db-3.sql`
- Modify: `src/main/java/com/breynisson/router/jdbc/DatabaseAdapter.java`, `src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java`
- Test: `src/test/java/com/breynisson/router/jdbc/McpEmbeddingDaoTest.java`

**Interfaces:**
- Produces:
  - `DatabaseAdapter.interface TransactionBody<T> { T run(Connection c) throws SQLException; }` and `static <T> T inTransaction(TransactionBody<T> body)` - autocommit off, commit on return, rollback and rethrow as `RouterException` on any exception.
  - `McpEmbeddingDao.TABLE = "MCP_EMBEDDING"`, `McpEmbeddingDao.TABLE_V2 = "MCP_EMBEDDING_V2"` (public constants).
  - `static void upsert(String table, McpEmbedding e)`; `static List<ScoredMatch> findSimilar(String table, float[] queryVector, float minScore, int topK, boolean unmigratedOnly)`; `static void deleteByModelNot(String table, String model)`. The existing no-table overloads delegate with `TABLE` (and `unmigratedOnly=false`).
  - Behaviour changes: `deleteByFilePath` and `deleteBySourceUrl` delete from **both** tables; `upsert(TABLE, ...)` sets `MIGRATED_AT = NULL` on conflict (not applicable to V2).

- [ ] **Step 1: Write failing tests** in `McpEmbeddingDaoTest` (a `pad1024` helper alongside the existing `pad`):
  - `migrationSchemaExists`: `MIGRATED_AT` column on `MCP_EMBEDDING` and table `MCP_EMBEDDING_V2` accept an insert of a 1024-dim vector.
  - `inTransactionRollsBackOnException`: insert inside `inTransaction`, throw, assert the row is absent; and commits when the body returns.
  - `upsertV2ThenFindSimilarV2ReturnsMatch`: `upsert(TABLE_V2, ...)` + `findSimilar(TABLE_V2, pad1024({1,0}), 0f, 10, false)` returns it.
  - `findSimilarUnmigratedOnlySkipsMarkedRows`: two old rows, one with `MIGRATED_AT` set via direct SQL; with `unmigratedOnly=true` only the unmarked one returns, with `false` both.
  - `deleteByFilePathAndSourceUrlRemoveV2Rows`: rows in both tables for the same path/source; after each delete call both tables are empty for it.
  - `reUpsertOfMigratedRowResetsMarker`: set `MIGRATED_AT`, `upsert(embedding)` again, assert `MIGRATED_AT IS NULL`.
- [ ] **Step 2: Run** `-Dtest=McpEmbeddingDaoTest`; expected FAIL (missing symbols / table).
- [ ] **Step 3: Create `digital-me-db-3.sql`** exactly as in the spec's "Schema" section (column, partial index `mcp_embedding_unmigrated_idx`, `MCP_EMBEDDING_V2` with HNSW index and source index) and finish with `INSERT INTO APPLICATION_METADATA ... ON CONFLICT (KEY) DO UPDATE SET VALUE = '3'` like `digital-me-db-2.sql`.
- [ ] **Step 4: Implement** `inTransaction` in `DatabaseAdapter` and the DAO changes above. Parametrise the existing SQL by table name (private constants only; table names never come from callers' input). In `upsert`, append `, MIGRATED_AT = NULL` to the `DO UPDATE SET` list only when `table.equals(TABLE)`.
- [ ] **Step 5: Run** `-Dtest=McpEmbeddingDaoTest,EmbeddingIndexTest,ResourceReceiverTest,ClaudeSessionIndexerTest`; expected all PASS.
- [ ] **Step 6: Commit** `feat: add MCP_EMBEDDING_V2 table, MIGRATED_AT column and table-parametrised DAO` (`git add` the new SQL file).

---

### Task 2: Migration DAO operations

**Files:**
- Modify: `src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java`
- Test: `src/test/java/com/breynisson/router/jdbc/McpEmbeddingDaoTest.java`

**Interfaces:**
- Consumes: Task 1 (`inTransaction`, `TABLE`, `TABLE_V2`, `upsert(table, e)`).
- Produces (all in `McpEmbeddingDao`):
  - `record ChunkToMigrate(int chunkIndex, String sourceUrl, String chunkText)`
  - `static List<String> findNextUnmigratedFilePaths(int limit)` - distinct `FILE_PATH` with unmigrated rows, ordered by tier (0: `SOURCE_URL` not matching `^https?://`; 1: host ending `.is`; 2: other), then newest `INDEXED_AT` first.
  - `static List<ChunkToMigrate> findUnmigratedChunks(String filePath)` - ordered by `CHUNK_INDEX`.
  - `static boolean commitMigration(String filePath, List<McpEmbedding> v2Rows, String migratedAt)` - one transaction: upsert all V2 rows, then `UPDATE MCP_EMBEDDING SET MIGRATED_AT=? WHERE FILE_PATH=? AND MIGRATED_AT IS NULL`; if updated count != `v2Rows.size()` roll back and return `false`, else `true`.
  - `static void upsertDual(List<McpEmbedding> oldRows, List<McpEmbedding> v2Rows, String migratedAt)` - one transaction: upsert V2 rows, upsert old rows with `MIGRATED_AT = migratedAt` (conflict update also sets it).
  - `record MigrationStatus(int migratedFiles, int remainingFiles, int migratedChunks, int remainingChunks)` and `static MigrationStatus migrationStatus()`.

- [ ] **Step 1: Write failing tests:**
  - `findNextUnmigratedOrdersLocalThenIcelandicThenOthers`: rows with sources `D:\diary\a.txt`, `https://www.visir.is/x`, `https://www.cnn.com/y`; expected order exactly that; a fully migrated file is excluded; `limit` respected.
  - `commitMigrationWritesV2RowsAndMarksOldRows`: 2 chunks old, commit 2 V2 rows → returns true, both old rows have `MIGRATED_AT` set, V2 has 2 rows.
  - `commitMigrationRollsBackWhenOldRowsChangedConcurrently`: delete one old chunk row between reading and committing → returns false, V2 has **zero** rows for the file, remaining old row still unmarked.
  - `commitMigrationIsNoopForAlreadyMigratedFile` (second call returns false, no duplicate rows).
  - `upsertDualMarksOldRowsMigratedImmediately`.
  - `migrationStatusCountsFilesAndChunks` for a mixed state (e.g. 1 migrated file with 2 chunks, 1 unmigrated with 3 → `(1,1,2,3)`).
- [ ] **Step 2: Run** `-Dtest=McpEmbeddingDaoTest`; expected FAIL.
- [ ] **Step 3: Implement** the methods. Tier via `CASE WHEN SOURCE_URL !~* '^https?://' THEN 0 WHEN SOURCE_URL ~* '^https?://[^/]*\.is(/|:|$)' THEN 1 ELSE 2 END`, aggregated per file with `MIN`; `GROUP BY FILE_PATH ORDER BY MIN(tier), MAX(INDEXED_AT) DESC LIMIT ?`.
- [ ] **Step 4: Run** `-Dtest=McpEmbeddingDaoTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: add MIGRATED_AT-based migration queries to McpEmbeddingDao`.

---

### Task 3: V2 settings, dual-write and fused search in `EmbeddingIndex`

**Files:**
- Create: `src/main/java/com/breynisson/router/mcp/V2Settings.java`
- Modify: `src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java`, `src/main/java/com/breynisson/router/AppConfig.java`, `src/main/resources/application.properties` (commented entries)
- Test: `src/test/java/com/breynisson/router/mcp/EmbeddingIndexTest.java`

**Interfaces:**
- Consumes: Tasks 1-2 DAO methods.
- Produces:
  - `public record V2Settings(EmbeddingClient client, String model, String documentPrefix, String queryPrefix, float minScore)`
  - `EmbeddingIndex` Spring constructor gains a trailing `Optional<V2Settings> v2` parameter; new convenience constructor `EmbeddingIndex(EmbeddingClient client, String dataDir, V2Settings v2)`; the existing two constructors keep working with no V2.
  - `EmbeddingIndex.migrationStatus(): Optional<McpEmbeddingDao.MigrationStatus>` (present iff V2 configured).
  - `AppConfig` bean `V2Settings v2Settings(...)` created only when `embedding.v2.enabled=true` (`@ConditionalOnProperty`), building its own `OllamaEmbeddingClient(ollamaUrl, embedding.v2.model, objectMapper)`. This client is **not** registered as an `EmbeddingClient` bean (that would make injection ambiguous). Properties: `embedding.v2.enabled` (false), `embedding.v2.model` (`bge-m3`), `embedding.v2.document-prefix` (empty), `embedding.v2.query-prefix` (empty), `embedding.v2.min-score` (`0.35`, to be calibrated in Task 6).

- [ ] **Step 1: Write failing tests** (`EmbeddingIndexTest`, using lambdas; V2 vectors padded to 1024 via a second helper):
  - `indexFileWithV2WritesBothTablesAndMarksMigrated`.
  - `indexFileWhenV2EmbedFailsWritesOldRowOnlyUnmarked` (v2 client returns null → old row `MIGRATED_AT IS NULL`, no V2 rows).
  - `indexFileWhenLegacyEmbedFailsWritesNothing` (existing behaviour preserved).
  - `findSimilarWithoutV2IgnoresMigratedMarker` (marked rows still returned when V2 not configured).
  - `findSimilarFusesDisjointSetsByRank`: unmigrated old file A and migrated V2 file B both match → both returned, rank-1-of-each-side items before rank-2.
  - `findSimilarWithOneModelUnavailableStillServesTheOtherSide`.
  - `findSimilarDeduplicatesSameSourceInBothTables` (one result, not two).
  - `findSimilarAppliesV2MinScoreToV2SideOnly` (V2 match below `v2.minScore` excluded while old side uses `minScore`).
  - `migrationStatusEmptyWithoutV2`.
- [ ] **Step 2: Run** `-Dtest=EmbeddingIndexTest`; expected FAIL.
- [ ] **Step 3: Implement.**
  - `indexFile`: legacy embedding as today; if `v2` present also embed each chunk (V2 prefix rule, unit-normalise); only if every V2 chunk succeeds call `upsertDual`, else the existing per-row old-table `upsert`.
  - `findSimilar`: embed the query per model; old side = `findSimilar(TABLE, q, minScore, topK, v2Present)`; V2 side = `findSimilar(TABLE_V2, q, v2.minScore, topK, false)`; merge by reciprocal rank fusion on `sourceUrl` (`score = Σ 1/(60+rank)`, rank starting at 1; keep the best-ranked entry's `filePath/chunkText` and its own cosine `score` for display), sort by fused score descending, trim to `topK`. Without V2 the result is exactly today's.
  - `indexAll`: apply `deleteByModelNot(TABLE_V2, v2.model)` is **not** done (see docs note in Task 6); everything else unchanged.
- [ ] **Step 4: Run** `-Dtest=EmbeddingIndexTest,SemanticSearchTest,McpSearchToolTest,AppConfigTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: dual-write and rank-fused search across nomic and bge-m3 tables`.

---

### Task 4: `EmbeddingMigrator` backfill worker

**Files:**
- Create: `src/main/java/com/breynisson/router/mcp/EmbeddingMigrator.java`
- Test: `src/test/java/com/breynisson/router/mcp/EmbeddingMigratorTest.java`

**Interfaces:**
- Consumes: `V2Settings`; `McpEmbeddingDao.findNextUnmigratedFilePaths/findUnmigratedChunks/commitMigration`.
- Produces: `@Component @ConditionalOnProperty(name = "embedding.v2.enabled", havingValue = "true") public class EmbeddingMigrator` with constructor `(V2Settings v2)`; `int migrateBatch(int limit)` (number of files committed); `@EventListener(ApplicationReadyEvent.class) void startOnReady()` starting a daemon thread `embedding-migrator` that loops `migrateBatch(25)`, sleeping 30 s when a batch committed nothing, and never exits on exceptions. It is skipped when `digitalme.migrate-sqlite-path` is non-blank (same exemption as `EmbeddingIndex`).

- [ ] **Step 1: Write failing tests** (isolated schema, V2 client lambda returning 1024-dim vectors, old rows inserted via the DAO):
  - `migrateBatchCommitsEachFileAndMarksChunks`: 3 files → returns 3, V2 has all chunks with `MODEL` = settings model, `migrationStatus().remainingFiles == 0`.
  - `migrateBatchEmbedsStoredChunkTextWithV2DocumentPrefix` (capture the texts passed to the client).
  - `embedFailureLeavesFileUntouchedAndOthersProceed`: client returns null for one file's text → that file has no V2 rows and stays unmarked, the others commit.
  - `ollamaDownCommitsNothingAndReturnsZero` (client always null).
  - `resumesAfterInterruption`: first migrator throws on the 2nd file's embedding (simulating a crash; catch in test), a **new** migrator instance then completes; final state complete, V2 row count equals chunk count (no duplicates).
  - `fileDeletedMidMigrationLeavesNoV2Rows`: client lambda deletes the file's old rows during embedding → batch count excludes it, no V2 rows for it.
  - `prioritisesLocalFilesBeforeWebPages` (observe client call order).
- [ ] **Step 2: Run** `-Dtest=EmbeddingMigratorTest`; expected FAIL.
- [ ] **Step 3: Implement.** Per file: read chunks, embed each via `v2.client()` (prefix rule, unit-normalise - reuse the normalisation in `EmbeddingIndex`, making it package-visible rather than duplicating), build `McpEmbedding`s with `v2.model()` and `Instant.now().toString()`, then `commitMigration`. Any null embedding or exception skips that file (log at warn once per batch, not per chunk).
- [ ] **Step 4: Run** `-Dtest=EmbeddingMigratorTest`; expected PASS.
- [ ] **Step 5: Commit** (`git add` the new files) `feat: add EmbeddingMigrator background backfill to bge-m3`.

---

### Task 5: Progress in `/health/index`

**Files:**
- Modify: `src/main/java/com/breynisson/router/ui/IndexPage.java`
- Test: `src/test/java/com/breynisson/router/ui/IndexPageTest.java`

**Interfaces:**
- Consumes: `EmbeddingIndex.migrationStatus()`.
- Produces: `/health/index` response keeps its four existing keys and, only when V2 is configured, adds `migration: { migratedFiles, remainingFiles, migratedChunks, remainingChunks, percentMigrated }` (`percentMigrated` = migrated chunks / total chunks × 100 rounded to one decimal, `100.0` when there are no chunks).

- [ ] **Step 1: Write failing tests:** `healthIndexOmitsMigrationWhenV2Disabled`; `healthIndexReportsMigrationProgressWhenV2Enabled` (2 migrated + 2 unmigrated single-chunk files → `percentMigrated` 50.0); `percentMigratedIs100WhenNoChunks`.
- [ ] **Step 2: Run** `-Dtest=IndexPageTest`; expected FAIL.
- [ ] **Step 3: Implement.** Replace `Map.of(...)` with a `LinkedHashMap` built in the method; nest the `migration` map.
- [ ] **Step 4: Run** `-Dtest=IndexPageTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: report bge-m3 migration progress in /health/index`.

---

### Task 6: Docs, properties and live calibration

**Files:**
- Modify: `CLAUDE.md`, `docs/architecture.md`, `docs/mcp.md`, `docs/testing.md`, `src/main/resources/application.properties`

- [ ] **Step 1: Update docs** per the spec's "Docs to update" list: project-structure line for `EmbeddingMigrator` and `V2Settings`; architecture sections for the schema (`digital-me-db-3.sql`), `EmbeddingIndex` (dual-write + fusion), `McpEmbeddingDao` (new operations, delete-from-both-tables), `EmbeddingMigrator`, `/health/index` `migration` block; `docs/mcp.md` Ollama setup (`ollama pull bge-m3`, `OLLAMA_IGPU_ENABLE=1` on Windows to use the Intel iGPU); `docs/testing.md` (1024-dim padding for V2, migrator tests). Document the known limitation: changing `embedding.v2.model` after migration needs a manual reset (`UPDATE MCP_EMBEDDING SET MIGRATED_AT = NULL; TRUNCATE MCP_EMBEDDING_V2`). Add the commented `embedding.v2.*` properties to `application.properties`.
- [ ] **Step 2: Full test run** `mvn test`; expected all PASS (record any pre-existing failures separately and compare with `main`).
- [ ] **Step 3: Manual live calibration (controller, not a subagent):** with `bge-m3` pulled and the GPU Ollama running, start the app against a scratch copy or the real DB with `embedding.v2.enabled=true`, let the migrator process a first slice (diary + Icelandic), then run the Icelandic queries from the experiment (`skilnaður gömul kærasta`, `gömul ný kærasta vinna æðar`) through `/semanticSearch` and tune `embedding.v2.min-score` (default 0.35) so real hits survive and noise is dropped; record the chosen value and rationale in `docs/architecture.md`.
- [ ] **Step 4: Commit** `docs: document bge-m3 migration, properties and calibrated min-score`.
