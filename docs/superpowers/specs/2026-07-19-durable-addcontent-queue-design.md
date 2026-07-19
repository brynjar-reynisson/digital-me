# Durable queue for /addContent HTTP submissions

## Problem

`POST /addContent` (called by the Chrome extension's `background.js` and the
screenshot-OCR pipeline's `send_to_digital_me()`) runs the full ingestion
pipeline synchronously on the HTTP request thread: Jsoup/`PageHandler`
extraction, writing the mcp-resources file, updating the Lucene index,
upserting `TEXT_ENTRY`, then submitting an async embedding job. If the JVM
dies while that synchronous work is in flight — before any of it has been
durably written — the submission is silently lost. Neither current caller
retries: `background.js` fires the `fetch()` and logs the unresolved
`Promise` without awaiting it; `screenshot-capture.py`'s `raise_for_status()`
only reacts to transport-level failures (connection refused/500), not
business-logic failure.

This is a narrow window in practice, but it's also the *only* remaining loss
path in the system. A prior investigation (this session, same day) confirmed
everything else already self-heals:
- Once `addContent()`'s synchronous work *does* complete and the HTTP
  response is sent, the content (mcp-resources file, Lucene, `TEXT_ENTRY`) is
  already durable — only the embedding vector might still be pending, and
  `EmbeddingIndex.indexAllOnStartup()` already re-embeds any not-yet-embedded
  mcp-resources file on every boot.
- `FileChangeWatcher` calls `storage.addContent()` in-process (not over
  HTTP); if the JVM dies mid-call there's no remote caller left to get an
  error, and its own scheduled re-scan (mtime vs. `TEXT_ENTRY.TIME`) retries
  the file on next boot regardless.
- `ClaudeSessionIndexer` doesn't go through `addContent()` at all — it has
  its own separate write path directly to `EmbeddingIndex`, and was already
  hardened against redundant/runaway re-processing in a separate fix
  (`bugfix/claude-session-reindex-storm`, PR #4).

## Scope

Only the two HTTP producers of `/addContent` (Chrome extension, screenshot-OCR
script). `FileChangeWatcher` keeps calling `storage.addContent()` directly,
in-process, exactly as it does today — it doesn't need this, since it's
already self-healing on restart independent of `addContent()`'s internals.

## Design

### New table: `digital-me-db-6.sql`

```sql
CREATE TABLE IF NOT EXISTS ADD_CONTENT_QUEUE (
    UUID        VARCHAR(60) NOT NULL PRIMARY KEY,
    PAYLOAD     TEXT        NOT NULL,
    RECEIVED_AT TEXT        NOT NULL,
    ATTEMPTS    INTEGER     NOT NULL DEFAULT 0
);
```

`PAYLOAD` is the incoming `AddContentRequest` serialized to JSON via Jackson
— the same shape already sent over HTTP today, so no new DTO. `RECEIVED_AT`
follows the existing ISO-8601 instant-string convention (`TEXT_ENTRY.TIME`,
`SUMMARY_CACHE.CREATED_AT`), via `DatabaseAdapter.instantToTime(Instant.now())`.
`ATTEMPTS` backs the poison-pill handling in the Failure handling section
below.

### `AddContentQueueDao` (new, package `com.breynisson.router.jdbc`)

Static-method style, mirroring `TextEntryDao`/`SummaryCacheDao`:

```java
public class AddContentQueueDao {
    public static String insert(String payloadJson) {
        String uuid = UUID.randomUUID().toString();
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO ADD_CONTENT_QUEUE (UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS) VALUES (?, ?, ?, 0)",
                uuid, payloadJson, DatabaseAdapter.instantToTime(Instant.now()));
        return uuid;
    }

    public static List<AddContentQueueEntry> findAllOrderedByReceivedAt() {
        return DatabaseAdapter.selectList(
                "SELECT UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS FROM ADD_CONTENT_QUEUE ORDER BY RECEIVED_AT ASC",
                new AddContentQueueEntry.ResultSetTransform());
    }

    public static void delete(String uuid) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM ADD_CONTENT_QUEUE WHERE UUID = ?", uuid);
    }

    public static void incrementAttempts(String uuid) {
        DatabaseAdapter.runPreparedStatement(
                "UPDATE ADD_CONTENT_QUEUE SET ATTEMPTS = ATTEMPTS + 1 WHERE UUID = ?", uuid);
    }
}
```

`AddContentQueueEntry` is a new POJO in `com.breynisson.router.jdbc.model`
(`uuid`, `payload`, `receivedAt`, `attempts`), matching the existing
`TextEntry`/`McpEmbedding` model style (public final fields, a nested
`ResultSetTransform`).

### `/addContent` HTTP handler change

`IndexPage.addContent()` no longer calls `storage.addContent()` directly. It
serializes the request body back to JSON and inserts it into the queue,
returning success immediately:

```java
@PostMapping(value = "/addContent", consumes = "application/json", produces = "application/json")
public AddContentResponse addContent(@RequestBody AddContentRequest addContentRequest) throws JsonProcessingException {
    AddContentQueueDao.insert(OBJECT_MAPPER.writeValueAsString(addContentRequest));
    AddContentResponse response = new AddContentResponse();
    response.setSuccess(true);
    return response;
}
```

The only durable step in the request path is now a single SQLite insert
(WAL-mode, already ACID) — not the multi-step extraction/Lucene/embedding
pipeline — so the crash-during-request window shrinks to essentially the
width of one `INSERT`. Response shape (`AddContentResponse`) is unchanged,
so neither caller needs any changes; both already ignore the response body
today (confirmed during design: `background.js` never awaits the `fetch`
promise, and `raise_for_status()` only checks HTTP status).

`DigitalMeStorage.addContent()` itself (the extraction/Lucene/embedding
pipeline) is unchanged and keeps its existing signature — it's now called
from the new queue processor (below) and, as today, directly from
`FileChangeWatcher`.

### `AddContentQueueProcessor` (new, package `com.breynisson.router`)

```java
@Component
public class AddContentQueueProcessor {
    private static final int MAX_ATTEMPTS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DigitalMeStorage storage;

    public AddContentQueueProcessor(DigitalMeStorage storage) {
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 2000)
    public void processPending() {
        for (AddContentQueueEntry entry : AddContentQueueDao.findAllOrderedByReceivedAt()) {
            try {
                AddContentRequest request = MAPPER.readValue(entry.payload, AddContentRequest.class);
                storage.addContent(request);
                AddContentQueueDao.delete(entry.uuid);
            } catch (Exception e) {
                handleFailure(entry, e);
            }
        }
    }

    private void handleFailure(AddContentQueueEntry entry, Exception e) {
        if (entry.attempts + 1 >= MAX_ATTEMPTS) {
            log.error("Dropping addContent queue entry {} after {} failed attempts", entry.uuid, MAX_ATTEMPTS, e);
            AddContentQueueDao.delete(entry.uuid);
        } else {
            log.warn("Failed to process addContent queue entry {} (attempt {})", entry.uuid, entry.attempts + 1, e);
            AddContentQueueDao.incrementAttempts(entry.uuid);
        }
    }
}
```

Single-threaded, sequential, `@Scheduled(fixedDelay = 2000)` — same shape as
`FileChangeWatcher` (5s) and `ClaudeSessionIndexer` (60s), just a shorter
interval since submitted content should become searchable quickly. No
"wake immediately on insert" mechanism (e.g. a notify/signal) — a 2-second
poll is cheap and simple, and every other scheduled component in this
codebase is pure polling; adding a new synchronization primitive to shave
~2 seconds of latency isn't worth the complexity.

Deleting the row only *after* `storage.addContent()` returns is what gives
the crash-safety property: if the JVM dies mid-processing, the row survives,
and the first poll after restart picks it up and retries — the same
principle as `FileChangeWatcher` re-scanning a file whose mtime is newer
than its recorded `TIME`.

Sequential processing naturally coexists with `FileChangeWatcher`'s direct,
concurrent in-process calls to `storage.addContent()` via the method's
existing `ReentrantLock` — unchanged by this design.

### Failure handling

`storage.addContent()` already catches its own internal exceptions and
returns `success: false` rather than throwing — an ordinary logged
application error, not a crash — so that row is deleted the same as any
other processed entry (matching the existing discard cases — e.g.
screenshot-covered or self-referential URLs — which also return
`success: true` with nothing indexed). The `try`/`catch` in
`processPending()` and the `ATTEMPTS` column exist only for failures
`storage.addContent()` doesn't already swallow — chiefly a corrupt or
non-deserializable `PAYLOAD`. On such a failure, `ATTEMPTS` is incremented;
after `MAX_ATTEMPTS` (5) failed attempts the entry is logged at `ERROR`
(visible, not silently dropped) and removed rather than retried forever.

## Testing

- **`AddContentQueueDaoTest`** (new, standard DAO test pattern — static
  `@TempDir` DB path + `DatabaseAdapter.setDefaultDatabasePath()`/`.init()`
  lifecycle, per `docs/testing.md`): `insert` then
  `findAllOrderedByReceivedAt` round-trips the payload; multiple inserts come
  back ordered by `RECEIVED_AT`; `delete` removes an entry and a subsequent
  find no longer returns it; `incrementAttempts` increments `ATTEMPTS` for
  the targeted row only.
- **`AddContentQueueProcessorTest`** (new): a queued row gets processed
  (assert via `TextEntryDao.findByName`/Lucene search on the resulting
  content, same assertions style as `DefaultDigitalMeStorageTest`) and
  removed from the queue afterward. A row inserted but never processed
  (simulating a crash before the scheduled tick ran) is still present and
  gets picked up by a fresh `processPending()` call, as if after a restart.
  A payload that fails to deserialize (poison pill) has its `ATTEMPTS`
  incremented on each call and is dropped with a logged error once
  `ATTEMPTS` reaches `MAX_ATTEMPTS`.
- **`IndexPageTest`**: existing `addContent`-related assertions (if any
  call `storage.addContent()` expectations directly) get updated to assert
  a row lands in `ADD_CONTENT_QUEUE` instead — the controller no longer
  calls `DigitalMeStorage.addContent()` synchronously.

## Docs

Update `docs/architecture.md`'s `/addContent` REST API row and
`DefaultDigitalMeStorage`/`FileChangeWatcher` prose to describe the new
queue-then-poll flow for HTTP submissions vs. `FileChangeWatcher`'s
unchanged direct in-process calls. Add `AddContentQueueDao` and
`AddContentQueueProcessor` bullets alongside the other DAOs/subsystems in
the Key Subsystems section, and add `ADD_CONTENT_QUEUE` to the Database
Schema section.

## Out of scope

- `FileChangeWatcher`'s direct in-process `addContent()` calls — unchanged.
- `ClaudeSessionIndexer` — doesn't call `addContent()`, unaffected.
- No change to `DigitalMeStorage.addContent()`'s own logic (extraction,
  `PageHandler` dispatch, embedding pool sizing from PR #5) — only *when*
  and *from which thread* it gets called changes.
- No "wake immediately on insert" signaling — a 2-second poll interval is
  the deliberate, simpler choice.
- No dead-letter table/directory for entries dropped after `MAX_ATTEMPTS` —
  they're logged at `ERROR` and discarded, consistent with how this
  codebase already surfaces this class of problem (e.g. `LayoutChangeReporter`
  writing an alert file rather than queuing for manual replay).
- No configurability for `MAX_ATTEMPTS` or the poll interval — both are
  fixed constants, unlike `embedding.executor.pool-size` (PR #5), since
  nothing in this design calls for tuning them per-deployment.
