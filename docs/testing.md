# Testing conventions

- JUnit 5; test class names end in `Test`
- Use `LuceneIndex.setIndexPath()` in `@BeforeEach` (with a `@TempDir`) to isolate Lucene state per test
- Use `LuceneIndex.deleteIndex()` in `@BeforeEach`/`@AfterEach` to reset index state
- Clean up DB rows explicitly in tests (no automatic rollback); delete before insert to guard against leftover state from prior failed runs
- `LuceneQuery.java` in `src/test/` is a manual query utility, not a test class

## Postgres tests

- DB tests connect to the Supabase local dev stack's Postgres instance (`localhost:54322`, database `postgres`, default `postgres`/`postgres` credentials — override via the `POSTGRES_PASSWORD` env var if yours differs) — the same instance `agent-suite` and `soulman` use, each in their own schema. This is a prerequisite for running `mvn test`, the same way some tests require Ollama running.
- Use `PostgresTestSupport.createIsolatedSchema("<prefix>")` in `@BeforeAll` to get a fresh, isolated schema per test class (replaces the old per-class SQLite `@TempDir` file); `PostgresTestSupport.dropSchema(schema)` in `@AfterAll` to clean up.
- `pgvector` is installed once into this shared instance's `extensions` schema (`CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions`, run by `DatabaseAdapter.init()`) — the extension's shared library must already be present on the Postgres server (see `docs/tooling.md`); it was confirmed available (v0.8.0) on this instance.

## MCP / embedding tests

- Use `EmbeddingClient` lambdas as mocks — no Mockito needed:
  - `text -> null` — simulates Ollama unavailable
  - `text -> new float[]{1.0f, 0.0f}` — deterministic embedding
- `OllamaEmbeddingClientTest` uses JDK built-in `com.sun.net.httpserver.HttpServer` to mock Ollama HTTP responses without extra test dependencies; starts on port 0 (OS-assigned) in `@BeforeEach` and stops in `@AfterEach`
- `EmbeddingIndexTest` uses `PostgresTestSupport.createIsolatedSchema()` in `@BeforeAll` and `PostgresTestSupport.dropSchema()` in `@AfterAll`, plus a separate instance `@TempDir` for file content
- `McpEmbeddingDaoTest` uses the same `PostgresTestSupport` lifecycle for Postgres schema isolation
- `MCP_EMBEDDING.EMBEDDING` is `VECTOR(1024)` and pgvector enforces the exact dimension, so test vectors are zero-padded to 1024 dims (padding both sides leaves cosine scores unchanged)
- `DatabaseAdapterMigrationTest` builds a schema at version 3 (via the package-private `DatabaseAdapter.initUpTo(3)`), seeds both embedding tables, then verifies `digital-me-db-4.sql`: the nomic table is dropped, the 1024-dim data survives as `MCP_EMBEDDING`, a re-applied/failed script rolls back atomically (transactional migrations), and a fresh database ends at version 4 with a single 1024-dim table
- `DeepseekSummarizeClientTest` only unit-tests the pure static helpers (`extractSummary()`, `sanitizeArgument()`) — no test spawns a real `opencode` process, so subprocess-handling correctness (PATHEXT resolution, stdin blocking, cmd.exe argument quoting) is verified manually against a live `opencode` install, not in CI
