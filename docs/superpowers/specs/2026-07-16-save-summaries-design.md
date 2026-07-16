# Cache on-demand summaries per source

## Problem

`POST /summarize` (called by the frontend once per top-5 semantic result, see
`docs/superpowers/specs/2026-07-16-summarize-top-results-in-parallel-design.md`)
re-invokes `SummarizeClient` every time, even when the underlying file hasn't
changed since it was last summarized. The default `SummarizeClient` implementation
(`DeepseekSummarizeClient`) shells out to `opencode`, which costs real money per call.
A summary should be computed once per file and reused until that file's content changes.

## Scope

Only the `/summarize` REST endpoint and the `SemanticSearch.summarize()` method it
calls. No MCP tool changes (`/summarize` is not exposed as an MCP tool — see
`docs/mcp.md`).

## Design

### Cache key: `source`, not chunk text

A semantic search snippet always originates from a chunk of an mcp-resources file
(`MCP_EMBEDDING.FILE_PATH`), but the physical file gets a new timestamped filename
every time its source is resubmitted (`ResourceReceiver.addContent()`). Only
`SOURCE_URL` (`SearchResult.source`) is a stable identity across resubmissions — the
same one already used throughout the codebase for dedup (`McpEmbeddingDao
.findFilePathsBySourceUrl`/`.deleteBySourceUrl`, `ExclusionRules`, semantic search's
own per-source dedup in `EmbeddingIndex.findSimilar`). The cache is therefore keyed by
`source`, independent of which chunk happened to score highest for a given query — the
requirement is "for that specific file," not "for that specific chunk."

### New table: `digital-me-db-5.sql`

```sql
CREATE TABLE SUMMARY_CACHE (
    SOURCE_URL TEXT NOT NULL PRIMARY KEY,
    SUMMARY    TEXT NOT NULL,
    CREATED_AT TEXT NOT NULL
);
```

`CREATED_AT` follows the existing ISO-8601 instant string convention used by `TIME`/
`INDEXED_AT` elsewhere (see `docs/architecture.md`'s Database Schema section), via
`DatabaseAdapter.instantToTime(Instant.now())`.

### `SummaryCacheDao` (new, package `com.breynisson.router.jdbc`)

Static-method style, mirroring `McpEmbeddingDao`:

```java
public class SummaryCacheDao {
    public static String find(String sourceUrl) {
        return DatabaseAdapter.selectOne(
                "SELECT SUMMARY FROM SUMMARY_CACHE WHERE SOURCE_URL = ?",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, sourceUrl);
    }

    public static void upsert(String sourceUrl, String summary) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT OR REPLACE INTO SUMMARY_CACHE (SOURCE_URL, SUMMARY, CREATED_AT) VALUES (?, ?, ?)",
                sourceUrl, summary, DatabaseAdapter.instantToTime(java.time.Instant.now()));
    }

    public static void deleteBySourceUrl(String sourceUrl) {
        DatabaseAdapter.runPreparedStatement(
                "DELETE FROM SUMMARY_CACHE WHERE SOURCE_URL = ?", sourceUrl);
    }
}
```

### `/summarize` request contract

`IndexPage`'s `SummarizeRequest` record gains an optional `source` field:

```java
record SummarizeRequest(String text, String source) {}
```

```java
@PostMapping(value = "/summarize", consumes = "application/json", produces = "application/json")
public SummarizeResponse summarize(@RequestBody SummarizeRequest request) {
    String summary = semanticSearch.summarize(request.text(), request.source());
    return new SummarizeResponse(summary != null ? summary : "");
}
```

`source` is optional (nullable) so any other hypothetical caller that only sends
`text` still works exactly as it does today (uncached, always calls
`SummarizeClient`) — only the frontend, which already has `source` in scope, needs to
change.

### `SemanticSearch.summarize(String text, String source)`

Replaces the current single-arg `summarize(String text)`:

```java
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

Per explicit decision: a failed or empty result (`null` or `""` — e.g. the
`opencode` 60-second timeout observed during the previous feature's manual testing)
is **not** cached, so the next request for that same file retries the real call
rather than permanently showing no summary for a transient failure.

### Frontend change (`frontend/src/App.tsx`)

`fetchSummary(source, snippet)` already has `source` in scope; its POST body gains
the field:

```ts
body: JSON.stringify({ text: snippet, source }),
```

No other frontend changes — the response shape (`{ summary }`) is unchanged.

### Invalidation — two call sites

Both are places that already delete-then-replace a source's indexed content; each
gets one added line, `SummaryCacheDao.deleteBySourceUrl(sourceUrl)`:

1. **`ResourceReceiver.deleteExistingFor(sourceUrl)`** (`src/main/java/com/breynisson/router/mcp/ResourceReceiver.java`) —
   called from `DefaultDigitalMeStorage.addContent()` before every write. Covers
   `/addContent` (Chrome extension web pages, screenshot OCR captures, and any other
   producer that funnels through `addContent()`).
2. **`ClaudeSessionIndexer`**'s stale-resource deletion loop (`src/main/java/com/breynisson/router/ClaudeSessionIndexer.java`,
   around the `McpEmbeddingDao.deleteByFilePath(file...)` call after matching
   `ResourceReceiver.firstLine(raw)` against the session's source URL) — this path
   does **not** go through `ResourceReceiver.deleteExistingFor()`, so without this
   second call site a Claude-session content update would leave a stale cached
   summary in place indefinitely.

No other content-update path exists: `FileChangeWatcher` (local `.txt`/`.md`/`.pdf`
files watched into `TEXT_ENTRY`/Lucene) never produces mcp-resources entries or
semantic-search results, so nothing summarized through this endpoint can originate
from that path.

### No TTL, no backfill

The cache entry lives until one of the two invalidation call sites above deletes it —
no time-based expiry. Existing summaries already generated before this feature ships
are not backfilled; the cache starts empty and populates naturally as results are
summarized going forward.

## Testing

- **`SummaryCacheDaoTest`** (new, mirrors `McpEmbeddingDaoTest`'s static-`@TempDir` +
  `DatabaseAdapter.setDefaultDatabasePath()`/`.init()` lifecycle): `find` returns
  `null` for an unknown source; `upsert` then `find` round-trips the summary;
  `upsert` twice for the same source replaces rather than duplicates (`INSERT OR
  REPLACE` on the `SOURCE_URL` primary key); `deleteBySourceUrl` removes the entry
  and a subsequent `find` returns `null`; `deleteBySourceUrl` for an unrelated source
  leaves other entries untouched.
- **`SemanticSearchTest`** (new — no test class exists for `SemanticSearch` today):
  constructed with `new EmbeddingIndex(text -> null, tempDir.toString())` (the
  existing test-only convenience constructor, avoiding a real Ollama dependency) and
  a `SummarizeClient` lambda mock, per this project's existing lambda-mock
  convention (see `docs/testing.md`). Cases:
  - First call for a `source` with no cache entry calls the mock and returns its
    result.
  - Second call for the same `source` returns the cached value without invoking the
    mock again (assert via a call-counting lambda).
  - A mock returning `null` (simulating an `opencode`/Ollama failure) is not cached —
    a subsequent call for the same `source` invokes the mock again.
  - A mock returning `""` is likewise not cached.
  - `source == null` never touches the cache — the mock is invoked every time,
    matching today's behavior.
- **`ResourceReceiver`/`ClaudeSessionIndexer` invalidation**: extend their existing
  test coverage (if any covers `deleteExistingFor`/the stale-file deletion loop) to
  assert a previously-`upsert`ed `SUMMARY_CACHE` row for that source is gone
  afterward. If no existing test exercises these methods' deletion side effects
  directly, add a focused test for each rather than skipping coverage.

## Docs

Update `docs/architecture.md`'s `SemanticSearch` bullet to mention the new
`summarize(text, source)` signature and the cache-then-invalidate behavior, and add a
`SummaryCacheDao` bullet alongside the other DAOs in the Key Subsystems section.
Update the Database Schema section to list the new `SUMMARY_CACHE` table.

## Out of scope

- No TTL/expiry mechanism.
- No backfill of summaries generated before this feature.
- No caching for callers that don't supply `source` (there are none today besides
  the frontend, but the contract stays backward-compatible for any future caller).
- No change to which files get summarized (still exactly the top 5 semantic results
  with a snippet, per the prior feature) — only whether a given `/summarize` call
  hits the real backend or a cache.
