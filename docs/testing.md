# Testing conventions

- JUnit 5; test class names end in `Test`
- DB tests use a `@TempDir`-based path; call `DatabaseAdapter.setDefaultDatabasePath()` + `DatabaseAdapter.init()` in `@BeforeAll` to set up the schema
- Call `DatabaseAdapter.setDefaultDatabasePath(null)` in `@AfterAll` to close the connection and release file locks (important on Windows)
- Use a **static** `@TempDir` for the DB path to get a fresh isolated database per test class
- Use `LuceneIndex.setIndexPath()` in `@BeforeEach` (with a `@TempDir`) to isolate Lucene state per test
- Use `LuceneIndex.deleteIndex()` in `@BeforeEach`/`@AfterEach` to reset index state
- Clean up DB rows explicitly in tests (no automatic rollback); delete before insert to guard against leftover state from prior failed runs
- `LuceneQuery.java` in `src/test/` is a manual query utility, not a test class

## MCP / embedding tests

- Use `EmbeddingClient` lambdas as mocks — no Mockito needed:
  - `text -> null` — simulates Ollama unavailable
  - `text -> new float[]{1.0f, 0.0f}` — deterministic embedding
- `OllamaEmbeddingClientTest` uses JDK built-in `com.sun.net.httpserver.HttpServer` to mock Ollama HTTP responses without extra test dependencies; starts on port 0 (OS-assigned) in `@BeforeEach` and stops in `@AfterEach`
- `EmbeddingIndexTest` uses a static `@TempDir` for the DB path and a separate instance `@TempDir` for file content
- `McpEmbeddingDaoTest` follows the same static `@TempDir` + `setDefaultDatabasePath()` + `init()` + `setDefaultDatabasePath(null)` lifecycle pattern
- `DeepseekSummarizeClientTest` only unit-tests the pure static helpers (`extractSummary()`, `sanitizeArgument()`) — no test spawns a real `opencode` process, so subprocess-handling correctness (PATHEXT resolution, stdin blocking, cmd.exe argument quoting) is verified manually against a live `opencode` install, not in CI
