# Cache On-Demand Summaries Per Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache the result of `POST /summarize` per source, so a file that has already been summarized isn't re-sent to the (often paid) `SummarizeClient` again — until that file's content actually changes, at which point the cached summary is discarded.

**Architecture:** A new `SUMMARY_CACHE` table keyed by `SOURCE_URL` (the same stable per-source identity used throughout the codebase for dedup). `SemanticSearch.summarize(text, source)` checks the cache before calling `SummarizeClient`, and stores only non-null/non-empty results. The two existing places that already delete-and-replace a source's indexed content (`ResourceReceiver.deleteExistingFor()` and `ClaudeSessionIndexer`'s stale-file cleanup) each get one added line to also purge the cached summary. The frontend's existing `fetchSummary()` call gains the `source` field it already has in scope.

**Tech Stack:** Spring Boot 3.3.11 / Java 19 backend (SQLite via `sqlite-jdbc`), React 19 / TypeScript 5 frontend.

## Global Constraints

- Cache key is `source` (the `SearchResult.source` / `SOURCE_URL` value), never chunk text — per spec, the requirement is "for that specific file," and only `source` is stable across resubmissions (the physical mcp-resources file path changes every time).
- A failed or empty summarize result (`null` or `""`) is never cached — it must be retried on the next request for that source (explicit decision, see spec).
- No TTL/expiry — a cache entry lives until one of the two invalidation call sites deletes it.
- No backfill of summaries generated before this feature ships.
- `source` is optional/nullable on the `/summarize` request — when absent, behavior is unchanged from today (always calls `SummarizeClient`, never touches the cache).
- Next DB migration file is `digital-me-db-5.sql` (four already exist: `digital-me-db-1.sql` through `digital-me-db-4.sql`).
- New tables use `CREATE TABLE IF NOT EXISTS` (the convention for a brand-new table — see `digital-me-db-2.sql`), not `DROP TABLE IF EXISTS` (that pattern is only used when an existing table's schema changes, e.g. `digital-me-db-4.sql`).
- Per project workflow rule (`CLAUDE.md`): run `/simplify` after changing source files, before committing.
- Frontend has no automated test suite — the one frontend change in this plan (Task 3) is verified via `npm run build`/`npm run lint`, not unit tests.
- Backend DB tests follow the established convention (`docs/testing.md`): static `@TempDir` DB path, `DatabaseAdapter.setDefaultDatabasePath()` + `.init()` in `@BeforeAll`, `DatabaseAdapter.setDefaultDatabasePath(null)` in `@AfterAll`, explicit row cleanup after each test.

---

### Task 1: `SUMMARY_CACHE` table + `SummaryCacheDao`

**Files:**
- Create: `src/main/resources/digital-me-db-5.sql`
- Create: `src/main/java/com/breynisson/router/jdbc/SummaryCacheDao.java`
- Test: `src/test/java/com/breynisson/router/jdbc/SummaryCacheDaoTest.java`

**Interfaces:**
- Produces: `SummaryCacheDao.find(String sourceUrl) -> String` (null if not cached)
- Produces: `SummaryCacheDao.upsert(String sourceUrl, String summary) -> void`
- Produces: `SummaryCacheDao.deleteBySourceUrl(String sourceUrl) -> void`

This task is self-contained — nothing else in the codebase references `SummaryCacheDao` yet, so it compiles and its own tests pass in isolation.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/digital-me-db-5.sql`:

```sql
CREATE TABLE IF NOT EXISTS SUMMARY_CACHE (
    SOURCE_URL TEXT NOT NULL PRIMARY KEY,
    SUMMARY    TEXT NOT NULL,
    CREATED_AT TEXT NOT NULL
);
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/breynisson/router/jdbc/SummaryCacheDaoTest.java`:

```java
package com.breynisson.router.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SummaryCacheDaoTest {

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

    private static void cleanup(String sourceUrl) {
        DatabaseAdapter.runSql("DELETE FROM SUMMARY_CACHE WHERE SOURCE_URL='" + sourceUrl + "'");
    }

    @Test
    void findReturnsNullForUnknownSource() {
        assertNull(SummaryCacheDao.find("http://never-cached.com"));
    }

    @Test
    void upsertThenFindRoundTripsTheSummary() {
        String source = "http://round-trip.com";
        SummaryCacheDao.upsert(source, "a summary");

        assertEquals("a summary", SummaryCacheDao.find(source));
        cleanup(source);
    }

    @Test
    void upsertTwiceForSameSourceReplacesRatherThanDuplicates() {
        String source = "http://replace-me.com";
        SummaryCacheDao.upsert(source, "old summary");
        SummaryCacheDao.upsert(source, "new summary");

        assertEquals("new summary", SummaryCacheDao.find(source));
        cleanup(source);
    }

    @Test
    void deleteBySourceUrlRemovesTheEntry() {
        String source = "http://delete-me.com";
        SummaryCacheDao.upsert(source, "a summary");

        SummaryCacheDao.deleteBySourceUrl(source);

        assertNull(SummaryCacheDao.find(source));
    }

    @Test
    void deleteBySourceUrlLeavesOtherEntriesUntouched() {
        String keep = "http://keep-cached.com";
        String drop = "http://drop-cached.com";
        SummaryCacheDao.upsert(keep, "keep summary");
        SummaryCacheDao.upsert(drop, "drop summary");

        SummaryCacheDao.deleteBySourceUrl(drop);

        assertEquals("keep summary", SummaryCacheDao.find(keep));
        assertNull(SummaryCacheDao.find(drop));
        cleanup(keep);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=SummaryCacheDaoTest` (see `docs/tooling.md` for this machine's Maven invocation, since `mvn` is not on PATH)
Expected: compile error — `SummaryCacheDao` does not exist yet.

- [ ] **Step 4: Write the minimal implementation**

Create `src/main/java/com/breynisson/router/jdbc/SummaryCacheDao.java`:

```java
package com.breynisson.router.jdbc;

import java.time.Instant;

public class SummaryCacheDao {

    private static final String TABLE = "SUMMARY_CACHE";

    public static String find(String sourceUrl) {
        return DatabaseAdapter.selectOne(
                "SELECT SUMMARY FROM " + TABLE + " WHERE SOURCE_URL = ?",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, sourceUrl);
    }

    public static void upsert(String sourceUrl, String summary) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT OR REPLACE INTO " + TABLE + " (SOURCE_URL, SUMMARY, CREATED_AT) VALUES (?, ?, ?)",
                sourceUrl, summary, DatabaseAdapter.instantToTime(Instant.now()));
    }

    public static void deleteBySourceUrl(String sourceUrl) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE SOURCE_URL = ?", sourceUrl);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=SummaryCacheDaoTest`
Expected: PASS (5/5 tests)

- [ ] **Step 6: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add src/main/resources/digital-me-db-5.sql src/main/java/com/breynisson/router/jdbc/SummaryCacheDao.java src/test/java/com/breynisson/router/jdbc/SummaryCacheDaoTest.java
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add SUMMARY_CACHE table and SummaryCacheDao"
```

---

### Task 2: Wire caching into `SemanticSearch.summarize()` and the `/summarize` endpoint

**Files:**
- Modify: `src/main/java/com/breynisson/router/digitalme/SemanticSearch.java`
- Modify: `src/main/java/com/breynisson/router/ui/IndexPage.java`
- Test: `src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java` (new — no test class exists for `SemanticSearch` today)

**Interfaces:**
- Consumes: `SummaryCacheDao.find/upsert` from Task 1
- Produces: `SemanticSearch.summarize(String text, String source) -> String` (replaces the current single-arg `summarize(String text)` — its only caller, `IndexPage`, is updated in this same task)

This task changes an existing method's signature, so it must update its sole call site (`IndexPage`) in the same task — splitting them would leave the project uncompilable between tasks.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java`:

```java
package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SemanticSearchTest {

    @TempDir
    static Path dbDir;

    @TempDir
    Path dataDir;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    private static void cleanup(String sourceUrl) {
        DatabaseAdapter.runSql("DELETE FROM SUMMARY_CACHE WHERE SOURCE_URL='" + sourceUrl + "'");
    }

    private SemanticSearch semanticSearch(Function<String, String> summarizer, AtomicInteger callCount) {
        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        return new SemanticSearch(embeddingIndex, text -> {
            callCount.incrementAndGet();
            return summarizer.apply(text);
        }, dataDir.toString());
    }

    @Test
    void firstCallForSourceInvokesSummarizerAndReturnsResult() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "a summary", calls);

        String result = semanticSearch.summarize("some text", "http://fresh-source.com");

        assertEquals("a summary", result);
        assertEquals(1, calls.get());
        cleanup("http://fresh-source.com");
    }

    @Test
    void secondCallForSameSourceReturnsCachedValueWithoutInvokingSummarizerAgain() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "a summary", calls);
        String source = "http://cached-source.com";

        semanticSearch.summarize("some text", source);
        String second = semanticSearch.summarize("different text", source);

        assertEquals("a summary", second);
        assertEquals(1, calls.get(), "summarizer should only be invoked once");
        cleanup(source);
    }

    @Test
    void nullResultIsNotCachedAndIsRetriedOnNextCall() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> null, calls);
        String source = "http://failing-source.com";

        String first = semanticSearch.summarize("some text", source);
        String second = semanticSearch.summarize("some text", source);

        assertNull(first);
        assertNull(second);
        assertEquals(2, calls.get(), "a failed call must be retried, not cached");
    }

    @Test
    void emptyResultIsNotCachedAndIsRetriedOnNextCall() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "", calls);
        String source = "http://empty-source.com";

        semanticSearch.summarize("some text", source);
        semanticSearch.summarize("some text", source);

        assertEquals(2, calls.get(), "an empty result must be retried, not cached");
    }

    @Test
    void nullSourceNeverTouchesTheCache() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "a summary", calls);

        semanticSearch.summarize("some text", null);
        semanticSearch.summarize("some text", null);

        assertEquals(2, calls.get(), "without a source, every call must invoke the summarizer");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SemanticSearchTest`
Expected: compile error — `SemanticSearch.summarize(String, String)` does not exist yet (only the single-arg overload does).

- [ ] **Step 3: Update `SemanticSearch.summarize()`**

In `src/main/java/com/breynisson/router/digitalme/SemanticSearch.java`, add the import:

```java
import com.breynisson.router.jdbc.SummaryCacheDao;
```

Replace:

```java
    /** Summarizes the given text; returns null if Ollama is unavailable. */
    public String summarize(String text) {
        return summarizeClient.summarize(text);
    }
```

with:

```java
    /**
     * Summarizes the given text, caching the result per source; returns null if the
     * backend is unavailable. A null/empty result is never cached, so a failed call
     * is retried on the next request for that source rather than permanently
     * showing no summary. source may be null (e.g. a caller with no known file
     * identity), in which case the cache is never consulted or written.
     */
    public String summarize(String text, String source) {
        if (source != null) {
            String cached = SummaryCacheDao.find(source);
            if (cached != null) {
                return cached;
            }
        }
        String summary = summarizeClient.summarize(text);
        if (source != null && summary != null && !summary.isEmpty()) {
            SummaryCacheDao.upsert(source, summary);
        }
        return summary;
    }
```

- [ ] **Step 4: Update `IndexPage`'s `/summarize` endpoint**

In `src/main/java/com/breynisson/router/ui/IndexPage.java`, replace:

```java
    record SummarizeRequest(String text) {}
    record SummarizeResponse(String summary) {}

    @PostMapping(value = "/summarize", consumes = "application/json", produces = "application/json")
    public SummarizeResponse summarize(@RequestBody SummarizeRequest request) {
        String summary = semanticSearch.summarize(request.text());
        return new SummarizeResponse(summary != null ? summary : "");
    }
```

with:

```java
    record SummarizeRequest(String text, String source) {}
    record SummarizeResponse(String summary) {}

    @PostMapping(value = "/summarize", consumes = "application/json", produces = "application/json")
    public SummarizeResponse summarize(@RequestBody SummarizeRequest request) {
        String summary = semanticSearch.summarize(request.text(), request.source());
        return new SummarizeResponse(summary != null ? summary : "");
    }
```

- [ ] **Step 5: Run the test to verify it passes, then run the full suite**

Run: `mvn test -Dtest=SemanticSearchTest`
Expected: PASS (5/5 tests)

Run: `mvn test`
Expected: all tests pass (verify via `target/surefire-reports/*.txt`, not the process exit code — see `docs/testing.md`'s note on this environment's harmless post-test JVM-fork-kill quirk). This confirms `IndexPage`'s changed call site compiles cleanly against the rest of the project.

- [ ] **Step 6: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/digitalme/SemanticSearch.java src/main/java/com/breynisson/router/ui/IndexPage.java src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: cache /summarize results per source"
```

---

### Task 3: Frontend sends `source` with each summarize request

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: the `/summarize` endpoint's new optional `source` field from Task 2 (response shape `{ summary }` is unchanged)

- [ ] **Step 1: Update `fetchSummary` in `frontend/src/App.tsx`**

Find:

```ts
  function fetchSummary(source: string, snippet: string) {
    fetch('/summarize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: snippet }),
    })
```

Replace with:

```ts
  function fetchSummary(source: string, snippet: string) {
    fetch('/summarize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: snippet, source }),
    })
```

No other lines in this function change.

- [ ] **Step 2: Run `/simplify`**

Per project workflow rule, invoke the `/simplify` slash command now that a source file has changed.

- [ ] **Step 3: Type-check and lint**

Run: `cd frontend && npm run build`
Expected: completes with no `tsc` errors.

Run: `cd frontend && npm run lint`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add frontend/src/App.tsx
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: send source with each /summarize request"
```

---

### Task 4: Invalidate the cache in `ResourceReceiver.deleteExistingFor()`

**Files:**
- Modify: `src/main/java/com/breynisson/router/mcp/ResourceReceiver.java`
- Modify: `src/test/java/com/breynisson/router/mcp/ResourceReceiverTest.java` (extends two existing tests)

**Interfaces:**
- Consumes: `SummaryCacheDao.upsert/find/deleteBySourceUrl` from Task 1

This covers every content update that goes through `DefaultDigitalMeStorage.addContent()` (Chrome extension web pages, screenshot OCR captures, and anything else routed through `/addContent`).

- [ ] **Step 1: Extend the existing tests to assert the cache is also invalidated**

In `src/test/java/com/breynisson/router/mcp/ResourceReceiverTest.java`, add the import:

```java
import com.breynisson.router.jdbc.SummaryCacheDao;
```

Update `deleteExistingForRemovesPriorFileAndEmbeddingForSameSource`:

```java
    @Test
    void deleteExistingForRemovesPriorFileAndEmbeddingForSameSource() throws IOException {
        ResourceReceiver receiver = new ResourceReceiver(dataDir.toString());
        String sourceUrl = "http://same-source.com";

        Path staleFile = receiver.addContent(AddContentRequests.of(sourceUrl, "Old", "old content"));
        McpEmbeddingDao.upsert(new McpEmbedding(staleFile.toAbsolutePath().toString(), 0, sourceUrl,
                "old content", embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        SummaryCacheDao.upsert(sourceUrl, "cached summary");

        receiver.deleteExistingFor(sourceUrl);

        assertFalse(Files.exists(staleFile), "Stale resource file should be deleted");
        assertTrue(McpEmbeddingDao.findFilePathsBySourceUrl(sourceUrl).isEmpty(),
                "Stale embedding rows should be deleted");
        assertNull(SummaryCacheDao.find(sourceUrl), "Cached summary should be deleted");
    }
```

Update `deleteExistingForLeavesOtherSourcesUntouched`:

```java
    @Test
    void deleteExistingForLeavesOtherSourcesUntouched() throws IOException {
        ResourceReceiver receiver = new ResourceReceiver(dataDir.toString());

        Path keepFile = receiver.addContent(AddContentRequests.of("http://keep.com", "Keep", "keep content"));
        McpEmbeddingDao.upsert(new McpEmbedding(keepFile.toAbsolutePath().toString(), 0, "http://keep.com",
                "keep content", embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        SummaryCacheDao.upsert("http://keep.com", "keep summary");

        receiver.deleteExistingFor("http://different-source.com");

        assertTrue(Files.exists(keepFile));
        assertFalse(McpEmbeddingDao.findFilePathsBySourceUrl("http://keep.com").isEmpty());
        assertEquals("keep summary", SummaryCacheDao.find("http://keep.com"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=ResourceReceiverTest`
Expected: `deleteExistingForRemovesPriorFileAndEmbeddingForSameSource` fails on the new `assertNull` (cache entry still present); the other test still passes at this point (asserting the cache entry survives already holds true even before `deleteExistingFor` touches it, since nothing has deleted it yet) — confirm this by reading the failure output, don't skip running it.

- [ ] **Step 3: Update `ResourceReceiver.deleteExistingFor()`**

In `src/main/java/com/breynisson/router/mcp/ResourceReceiver.java`, add the import:

```java
import com.breynisson.router.jdbc.SummaryCacheDao;
```

Replace:

```java
    public void deleteExistingFor(String sourceUrl) {
        Set<String> filePaths = McpEmbeddingDao.findFilePathsBySourceUrl(sourceUrl);
        McpEmbeddingDao.deleteBySourceUrl(sourceUrl);
        for (String filePath : filePaths) {
            try {
                Files.deleteIfExists(Paths.get(filePath));
            } catch (IOException e) {
                log.warn("Error deleting stale resource file {}", filePath, e);
            }
        }
    }
```

with:

```java
    public void deleteExistingFor(String sourceUrl) {
        Set<String> filePaths = McpEmbeddingDao.findFilePathsBySourceUrl(sourceUrl);
        McpEmbeddingDao.deleteBySourceUrl(sourceUrl);
        SummaryCacheDao.deleteBySourceUrl(sourceUrl);
        for (String filePath : filePaths) {
            try {
                Files.deleteIfExists(Paths.get(filePath));
            } catch (IOException e) {
                log.warn("Error deleting stale resource file {}", filePath, e);
            }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=ResourceReceiverTest`
Expected: PASS (all tests in the class)

- [ ] **Step 5: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/mcp/ResourceReceiver.java src/test/java/com/breynisson/router/mcp/ResourceReceiverTest.java
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "fix: invalidate cached summary when a source's content is replaced"
```

---

### Task 5: Invalidate the cache in `ClaudeSessionIndexer`'s stale-file cleanup

**Files:**
- Modify: `src/main/java/com/breynisson/router/ClaudeSessionIndexer.java`
- Modify: `src/test/java/com/breynisson/router/ClaudeSessionIndexerTest.java`

**Interfaces:**
- Consumes: `SummaryCacheDao.upsert/find/deleteBySourceUrl` from Task 1

`ClaudeSessionIndexer.deleteOldResourceFiles()` does its own find-and-delete for stale Claude-session resource files and does **not** go through `ResourceReceiver.deleteExistingFor()` — so without this task, a Claude session content update would leave a stale cached summary in place indefinitely, unlike every other source type.

- [ ] **Step 1: Make `deleteOldResourceFiles` testable**

In `src/main/java/com/breynisson/router/ClaudeSessionIndexer.java`, change its visibility from `private` to package-private (no modifier) — the same pattern already used for `buildFileName` in this class, specifically so tests in the same package can call it directly without walking a real `~/.claude/projects` directory (which `CLAUDE_PROJECTS` hardcodes and which `indexAll()`/`indexSession()` are not designed to have injected in tests):

```java
    void deleteOldResourceFiles(String sourceUrl) {
```

(was `private void deleteOldResourceFiles(String sourceUrl) {`)

- [ ] **Step 2: Write the failing test**

In `src/test/java/com/breynisson/router/ClaudeSessionIndexerTest.java`, replace the whole file with:

```java
package com.breynisson.router;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionIndexerTest {

    @TempDir
    static Path dbDir;

    @TempDir
    Path dataDir;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    @Test
    void buildFileNameUsesDayHourMinuteSecondPrefixLikeOtherMcpResources() {
        LocalDateTime sessionStart = LocalDateTime.of(2026, 7, 16, 16, 15, 13);

        String fileName = ClaudeSessionIndexer.buildFileName("digital-me", sessionStart);

        assertEquals("16-16-15-13-claudecode-digital-me.txt", fileName);
    }

    @Test
    void deleteOldResourceFilesRemovesCachedSummaryForThatSource() {
        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        ClaudeSessionIndexer indexer = new ClaudeSessionIndexer(embeddingIndex, dataDir.toString());
        String sourceUrl = "claude://some-project/some-session";
        SummaryCacheDao.upsert(sourceUrl, "cached summary");

        indexer.deleteOldResourceFiles(sourceUrl);

        assertNull(SummaryCacheDao.find(sourceUrl));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=ClaudeSessionIndexerTest`
Expected: `deleteOldResourceFilesRemovesCachedSummaryForThatSource` fails — the cached summary is still present, since `deleteOldResourceFiles` doesn't touch `SummaryCacheDao` yet. (`buildFileNameUsesDayHourMinuteSecondPrefixLikeOtherMcpResources` continues to pass unchanged.)

- [ ] **Step 4: Update `deleteOldResourceFiles`**

In `src/main/java/com/breynisson/router/ClaudeSessionIndexer.java`, add the import:

```java
import com.breynisson.router.jdbc.SummaryCacheDao;
```

Replace:

```java
    void deleteOldResourceFiles(String sourceUrl) {
        if (!Files.isDirectory(mcpResourcesDir)) return;
```

with:

```java
    void deleteOldResourceFiles(String sourceUrl) {
        SummaryCacheDao.deleteBySourceUrl(sourceUrl);
        if (!Files.isDirectory(mcpResourcesDir)) return;
```

(The rest of the method body is unchanged. The cache delete runs unconditionally, matching how this method already runs on every `indexSession()` pass regardless of whether a stale file actually exists — a `DELETE` with no matching row is a harmless no-op.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn test -Dtest=ClaudeSessionIndexerTest`
Expected: PASS (both tests)

- [ ] **Step 6: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/ClaudeSessionIndexer.java src/test/java/com/breynisson/router/ClaudeSessionIndexerTest.java
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "fix: invalidate cached summary when a Claude session is re-indexed"
```

---

### Task 6: Update documentation

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:** none (docs-only)

- [ ] **Step 1: Update the `SemanticSearch` bullet**

Find, in the `### \`SemanticSearch\`` section:

```markdown
- `summarize(text)`: delegates to `SummarizeClient`; returns null when the backend is unavailable
```

Replace with:

```markdown
- `summarize(text, source)`: checks `SummaryCacheDao` for a cached summary for `source` first; on a miss, delegates to `SummarizeClient` and caches a non-null/non-empty result. Returns null when the backend is unavailable. `source` may be null, in which case the cache is never consulted or written (always calls `SummarizeClient`)
```

- [ ] **Step 2: Add a `SummaryCacheDao` bullet**

After the existing `### \`McpEmbeddingDao\`` section (ends with the `findFilePathsBySourceUrl(sourceUrl)` / `deleteBySourceUrl(sourceUrl)` bullet) and before `### \`SemanticSearch\``, insert:

```markdown
### `SummaryCacheDao`
- `find(sourceUrl)` — returns the cached summary for a source, or `null` if not cached
- `upsert(sourceUrl, summary)` — INSERT OR REPLACE into `SUMMARY_CACHE`, keyed by `SOURCE_URL`
- `deleteBySourceUrl(sourceUrl)` — called by `ResourceReceiver.deleteExistingFor()` and `ClaudeSessionIndexer`'s stale-file cleanup whenever a source's content is replaced, so a re-summarized file never serves a stale cached summary
```

- [ ] **Step 3: Add the new table to the Database schema section**

Find:

```markdown
MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING BLOB, MODEL, INDEXED_AT, PK(FILE_PATH, CHUNK_INDEX))  -- chunked vector embeddings
```

Replace with:

```markdown
MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING BLOB, MODEL, INDEXED_AT, PK(FILE_PATH, CHUNK_INDEX))  -- chunked vector embeddings
SUMMARY_CACHE (SOURCE_URL PK, SUMMARY, CREATED_AT)  -- cached on-demand summaries, discarded when a source's content is replaced
```

- [ ] **Step 4: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document SummaryCacheDao and the SUMMARY_CACHE table"
```

---

### Task 7: End-to-end verification against the real backend

**Files:** none (no code changes; this task runs the app and observes behavior)

**Interfaces:**
- Consumes: the running backend's `/summarize` and `/addContent` endpoints, and the `SUMMARY_CACHE` table created in Task 1

Unlike the previous feature (frontend rendering), this feature's core logic is 100% backend and already has full JUnit coverage (Tasks 1, 2, 4, 5) — this task exists to confirm the real, end-to-end wiring (HTTP endpoint -> cache -> real SQLite DB) behaves the same way outside of test mocks, using the actual configured `SummarizeClient` and database.

- [ ] **Step 1: Confirm the migration applied**

With the backend running (or after starting it — see `docs/architecture.md`'s Build and Run section for the working-directory requirement), inspect the live SQLite DB for the new table:

```bash
sqlite3 "<dataDir>/digital-me.db" ".tables"
```

Expected: `SUMMARY_CACHE` appears in the table list (find `<dataDir>` from wherever this deployment's `data.dir` property points — see the currently-running instance's config, not the repo's gitignored `digital-me-dev/`).

- [ ] **Step 2: Verify a cache hit skips the real summarizer**

Pick a `source` known to already be indexed (e.g. from a live `/semanticSearch?keywords=...` call), and call `/summarize` twice with the same `source`:

```bash
curl -s -X POST http://localhost:8080/summarize -H "Content-Type: application/json" \
  -d '{"text":"<some snippet text>","source":"<the source url>"}'
```

Time both calls. Expected: the first call takes as long as a real summarization call normally does (seconds to tens of seconds, depending on the configured `SummarizeClient`); the second call for the identical `source` returns near-instantly (a local SQLite `SELECT`, not a subprocess/HTTP round trip) with the same `summary` text as the first call.

- [ ] **Step 3: Verify the cache row exists directly in the DB**

```bash
sqlite3 "<dataDir>/digital-me.db" "SELECT SOURCE_URL, SUMMARY FROM SUMMARY_CACHE WHERE SOURCE_URL = '<the source url>';"
```

Expected: one row, with `SUMMARY` matching the value returned by step 2.

- [ ] **Step 4: Verify invalidation on content update**

Resubmit the same `source` with different content through `/addContent`:

```bash
curl -s -X POST http://localhost:8080/addContent -H "Content-Type: application/json" \
  -d '{"source":"<the source url>","name":"test","content":"updated content, different from before"}'
```

Then re-check the cache table:

```bash
sqlite3 "<dataDir>/digital-me.db" "SELECT SOURCE_URL FROM SUMMARY_CACHE WHERE SOURCE_URL = '<the source url>';"
```

Expected: no row returned — `ResourceReceiver.deleteExistingFor()` (Task 4) removed the cache entry as part of replacing that source's content.

- [ ] **Step 5: Verify a subsequent /summarize call for that source is a fresh (uncached) call**

Repeat step 2's `curl` call for the same `source` (now with its new content/snippet). Expected: this call takes real summarization time again (not instant), confirming the cache was genuinely cleared rather than merely appearing empty in the DB query.

- [ ] **Step 6: Run the full backend test suite one final time**

Run: `mvn test`
Expected: all tests pass (verify via `target/surefire-reports/*.txt`, per this environment's known harmless non-zero exit code after tests complete — see `docs/testing.md`).
