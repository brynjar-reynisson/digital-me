# bge-m3 Cleanup and File-Size Cap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the bge-m3 migration: collapse to a single embedding table/model (bge-m3, 1024-d), delete the migration machinery, add a max-file-size cap to the embedding scan, and make `/health/index` coverage numbers correct again.

**Architecture:** `digital-me-db-4.sql` drops the legacy nomic table `MCP_EMBEDDING` and renames `MCP_EMBEDDING_V2` to `MCP_EMBEDDING` (with its indexes). `DatabaseAdapter.init()` applies each pending script and its version bump in ONE transaction so db-4 can never be half-applied or re-applied. Code is simplified back to a single table/model: no `V2Settings`, no `embedding.v2.*` flag, no `EmbeddingMigrator`, no fusion/completeness logic.

**Tech Stack:** Java 19, Spring Boot 3.3.11, PostgreSQL + pgvector, JUnit 5, Ollama (`bge-m3`).

**Spec:** `docs/superpowers/specs/2026-10-30-bge-m3-embedding-migration-design.md` (sections "Cutover and cleanup" and the 2026-10-30 amendment). Predecessor plan: `docs/superpowers/plans/2026-10-30-bge-m3-embedding-migration.md`.

## Global Constraints

- Build/test: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dskip.installnodenpm -Dskip.npm -Dtest=<Class>`. NEVER run `package` or `clean`: the live app is running from this worktree's `target/` jar.
- DB tests need the Supabase Postgres at `localhost:54322`; use `PostgresTestSupport.createIsolatedSchema` / `dropSchema`. Test embedding vectors are zero-padded to **1024** dims (the single table is `VECTOR(1024)` after db-4).
- `DatabaseAdapter.runSql` splits scripts on `;` - no semicolons inside SQL comments or string literals in migration scripts.
- The embedding model is `bge-m3` (no task prefixes: `ollama.embedding.document-prefix` / `query-prefix` default empty; where set, text is `prefix + " " + text`). Blank-after-normalisation chunks are skipped (never sent to Ollama, never stored): keep `OllamaEmbeddingClient.isBlankAfterNormalisation` and its use in `EmbeddingIndex.indexFile`.
- `semantic-search.min-score` default becomes `0.35` (the value the V2 side uses today; calibration is a separate later decision).
- Checkstyle: no unused imports, no empty catch blocks, one statement per line.
- Do NOT touch the running app, the real database, or deploy: running the app against the real DB executes db-4 (drops the nomic table, irreversible). Tests only use isolated schemas.
- Work in the worktree on branch `feature/bge-m3-embedding-migration` (PR #11). `git add` new files. Do not push. Commit trailers: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01UcD12ZNijJc2eptZG4zru3`. `docs/architecture.md` is CRLF and the repo has `* text=auto`: after editing verify `git diff --stat HEAD -- docs/architecture.md` shows only the intended lines; if the whole file shows changed, restore CRLF and stage with `git hash-object -w --no-filters` + `git update-index --cacheinfo`.

## Review Focus

- db-4 run against a database that already went through db-3 with real rows (V2 data, legacy data, `MIGRATED_AT` set): V2 rows survive with data intact, legacy rows are gone, the new `MCP_EMBEDDING` has the HNSW index and works with `findSimilar`.
- db-4 failing mid-way must leave the schema at version 3 untouched (transaction rollback), and re-running it on an already-migrated schema must FAIL SAFELY (rollback, no data loss), never drop the new table.
- A fresh database (scripts 1-4 in sequence) ends with a single 1024-d `MCP_EMBEDDING` and version 4.
- `deleteByFilePath`/`deleteBySourceUrl`/`findFilePathsBySourceUrl` still work on the single table (previously both tables).
- Files above the size cap are skipped by both the startup scan and `indexFile`, and must not be re-attempted in a loop (they have no rows, so the scan sees them each start - acceptable only if the skip is cheap: no read of file contents).

---

### Task 1: Collapse to a single table and model

**Files:**
- Create: `src/main/resources/digital-me-db-4.sql`
- Modify: `src/main/java/com/breynisson/router/jdbc/DatabaseAdapter.java`, `jdbc/McpEmbeddingDao.java`, `mcp/EmbeddingIndex.java`, `mcp/OllamaEmbeddingClient.java`, `AppConfig.java`, `ui/IndexPage.java`, `migration/SqliteToPostgresMigrator.java`, `src/main/resources/application.properties`
- Delete: `mcp/EmbeddingMigrator.java`, `mcp/V2Settings.java`, and their tests (`EmbeddingMigratorTest`); remove tests/code for fusion-free paths that no longer exist
- Test: `DatabaseAdapterMigrationTest` (new cases), `McpEmbeddingDaoTest`, `EmbeddingIndexTest`, `IndexPageTest`, `SqliteToPostgresMigratorTest`, plus every test that pads vectors to 768 (`ClaudeSessionIndexerTest`, `SemanticSearchTest`, `EmbeddingIndexTest`, ...)

**Interfaces:**
- Produces: `McpEmbeddingDao` operating on the single `MCP_EMBEDDING` table with the original public static API (`upsert(McpEmbedding)`, `findSimilar(float[] queryVector, float minScore, int topK)`, `findAllFilePaths()`, `findFilePathsBySourceUrl(String)`, `deleteByFilePath(String)`, `deleteBySourceUrl(String)`, `deleteByModelNot(String)`, `countIndexedFiles()`, `countTotalChunks()`); no table parameters, no `TABLE_V2`, no `MigrationStatus`/`commitMigration`/`findNextUnmigratedFilePaths`/`findUnmigratedChunks`/`unmigratedOnly`. `EmbeddingIndex` keeps its public API (`indexFile(Path)`, `findSimilar(String query, int topK)`, `countFilesOnDisk()`, `ScoredResult`) and the constructors tests use (`new EmbeddingIndex(client, dataDir)` etc.) minus V2 variants. `/health/index` returns the original four keys only.

- [ ] **Step 1: Write failing tests**
  - `DatabaseAdapterMigrationTest.db4DropsLegacyAndKeepsV2DataAsMcpEmbedding`: build a schema at version 3 (run scripts 1-3 via the existing helper or `createIsolatedSchema` then re-pointing is not possible - so instead create the isolated schema, which already applies ALL scripts, and write the test against an explicit helper that applies scripts 1-3 only; if that helper does not exist add a package-visible `DatabaseAdapter.initUpTo(int version)` used by tests), insert one legacy row (768-d, `MIGRATED_AT` set) and one V2 row (1024-d), then run the remaining scripts via `init()`; assert the V2 row is now in `MCP_EMBEDDING` (1024-d vector, data intact), `MCP_EMBEDDING_V2` no longer exists, the legacy rows are gone, the `hnsw` index exists on `MCP_EMBEDDING` and `findSimilar` returns the row.
  - `db4IsAtomicAndNotReappliedUnsafely`: with a schema already at version 4, forcing the stored version back to 3 and calling `init()` must throw (rename fails) and leave `MCP_EMBEDDING` and its rows intact (transaction rolled back).
  - `freshDatabaseEndsAtVersion4WithSingle1024Table`.
  - Update `McpEmbeddingDaoTest`, `EmbeddingIndexTest`, `IndexPageTest` and the other vector-padding tests: 768 -> 1024; remove tests for removed behaviour (dual tables, fusion, completeness cache, migration status, MIGRATED_AT, V2 settings, throttle); `IndexPageTest`: `/health/index` has exactly the four original keys and `coveragePercent` reflects `indexedFiles / totalFilesOnDisk` (100.0 when no files).
- [ ] **Step 2: Run** the new/changed classes; expected FAIL (missing script/helper, 768-vs-1024).
- [ ] **Step 3: Implement `digital-me-db-4.sql`**: drop the legacy `MCP_EMBEDDING` (and with it its HNSW index and `MIGRATED_AT`), rename `MCP_EMBEDDING_V2` to `MCP_EMBEDDING`, rename its indexes to `mcp_embedding_hnsw_idx` / `mcp_embedding_source_idx` (drop the old index names first as needed), set `database.version` to `4`. Plain statements only (no `IF EXISTS` on the rename: it must fail loudly if re-applied), comments without semicolons.
- [ ] **Step 4: Implement atomic script application in `DatabaseAdapter.init()`**: each pending script and its `database.version` update run on ONE connection in ONE transaction (commit on success, rollback and rethrow `RouterException` on any failure); reuse the existing statement-splitting logic (refactor `runSql` into a variant taking a `Connection`). A fresh database still runs scripts 1..N in order, each in its own transaction. Keep the existing `UPDATE APPLICATION_METADATA ... max` behaviour only if still needed after this change (the version is now written by each script / the transaction helper, never lowered).
- [ ] **Step 5: Simplify the code** to the single table and model: `McpEmbeddingDao` back to one table constant (`MCP_EMBEDDING`) with the API in Interfaces; delete `EmbeddingMigrator`, `V2Settings`, the V2 branches in `EmbeddingIndex` (dual config, `migrationComplete`, fusion leftovers, `searchedWithin`/`lastSearchMillis`, `migrationStatus`), the `V2Settings` bean and `embedding.v2.*` properties in `AppConfig`/`application.properties`, the `migration` block in `IndexPage` (keep `roundedPercent` for coverage). `EmbeddingIndex` defaults: model `bge-m3`, prefixes empty, `semantic-search.min-score` default `0.35`. `OllamaEmbeddingClient` default model `bge-m3`. Keep blank-chunk skipping in `indexFile` for all chunks. Fix `SqliteToPostgresMigrator` so it still compiles and its test passes against the 1024-d table (its embedding pad length becomes 1024).
- [ ] **Step 6: Run** the full suite (`mvn test` with the skip flags); expected all PASS. Record the count.
- [ ] **Step 7: Commit** (`git add` new files) `refactor: collapse embeddings to a single bge-m3 table and drop the migration machinery`.

---

### Task 2: File-size cap for the embedding scan

**Files:**
- Modify: `src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java`, `src/main/resources/application.properties`
- Test: `src/test/java/com/breynisson/router/mcp/EmbeddingIndexTest.java`

**Interfaces:**
- Consumes: Task 1's `EmbeddingIndex`.
- Produces: property `embedding.max-file-bytes` (default `1048576`; `0` or negative disables the cap) read by the Spring `EmbeddingIndex` constructor; `indexAll()` and `indexFile(Path)` skip any file whose `Files.size` exceeds the cap WITHOUT reading its contents, logging one INFO line per skipped file (`Skipping oversized file for embedding (<n> bytes > cap <cap>): <path>`).

- [ ] **Step 1: Write failing tests** in `EmbeddingIndexTest`: `indexFileSkipsFileOverSizeCap` (cap 100 bytes, file of 500 bytes: embedding client lambda never called, no rows); `indexFileIndexesFileAtOrUnderCap`; `indexAllSkipsOversizedFilesAndStillIndexesOthers` (two files on disk, one oversized: only the small one gets rows, client call counter proves the big file was never read/embedded); `capZeroDisablesTheCheck`. Use a package-visible constructor/parameter to set the cap in tests.
- [ ] **Step 2: Run** `-Dtest=EmbeddingIndexTest`; expected FAIL.
- [ ] **Step 3: Implement** the cap: new constructor parameter (Spring `@Value("${embedding.max-file-bytes:1048576}") long maxFileBytes`), check at the top of `indexFile` before reading, and make `indexAll` skip such files before calling `indexFile`. Add the commented property with explanation to `application.properties`.
- [ ] **Step 4: Run** `-Dtest=EmbeddingIndexTest,SemanticSearchTest,McpSearchToolTest,IndexPageTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: skip oversized files in the embedding scan`.

---

### Task 3: Docs

**Files:**
- Modify: `CLAUDE.md`, `docs/architecture.md`, `docs/mcp.md`, `docs/testing.md`, `docs/superpowers/specs/2026-10-30-bge-m3-embedding-migration-design.md`, `docs/tooling.md` (only if it mentions nomic/768)

- [ ] **Step 1: Update docs to the post-cleanup reality** (verify every claim against the code): architecture.md schema section (add `digital-me-db-3.sql` and `digital-me-db-4.sql` summaries; `MCP_EMBEDDING` is now `VECTOR(1024)`; remove the nomic/768 statements and the "fixed to nomic-embed-text" paragraph), `EmbeddingIndex` / `McpEmbeddingDao` / `OllamaEmbeddingClient` descriptions (bge-m3 default, no prefixes, single table, size cap `embedding.max-file-bytes`, blank-chunk skipping), remove the `EmbeddingMigrator` and `V2Settings` entries and the `/health/index` `migration` block (coverage numbers are single-table and accurate again), add the transactional-migration behaviour of `DatabaseAdapter.init()` and the note that db-4 is irreversible (the nomic table is dropped; rolling back to an older jar is not possible). `docs/mcp.md`: Ollama setup uses `ollama pull bge-m3` only (nomic no longer required), keep the Intel iGPU note (`OLLAMA_IGPU_ENABLE=1`). `docs/testing.md`: 1024-dim padding, no migrator tests. `CLAUDE.md`: project-structure lines and tech-stack row ("Semantic search: Ollama local AI (`bge-m3`...)"). Spec: append "Amendment 2026-11-01 (cleanup implemented)" - what was removed, db-4 + atomic migrations, the size cap, and that the Review/cutover happened on 2026-11-01.
- [ ] **Step 2: Verify** `git diff --stat HEAD -- docs/architecture.md` shows only the intended lines (CRLF intact), and `grep -rn "nomic\|V2Settings\|EmbeddingMigrator\|MCP_EMBEDDING_V2\|embedding.v2" CLAUDE.md docs src/main` shows only intentional historical mentions (the specs/plans/amendment sections and db-1..db-3 scripts).
- [ ] **Step 3: Commit** `docs: document the single-table bge-m3 setup, db-4 and the file-size cap`.
