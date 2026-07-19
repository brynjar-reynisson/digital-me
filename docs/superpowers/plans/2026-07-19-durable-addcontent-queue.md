# Durable /addContent Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make HTTP submissions to `/addContent` (Chrome extension, screenshot-OCR script) crash-safe by durably queuing the raw request in SQLite before doing any real work, instead of processing it synchronously on the request thread.

**Architecture:** `IndexPage.addContent()` now only serializes the request to JSON and inserts it into a new `ADD_CONTENT_QUEUE` table, then returns success immediately. A new `@Scheduled` component, `AddContentQueueProcessor`, polls that table every 2 seconds, deserializes each row, and calls the existing `DigitalMeStorage.addContent()` pipeline (extraction, Lucene, `TEXT_ENTRY`, embedding submission — all unchanged) — deleting the row only after that call returns, so a crash mid-processing leaves the row for the next poll (post-restart) to retry. `FileChangeWatcher` keeps calling `DigitalMeStorage.addContent()` directly, in-process, unchanged.

**Tech Stack:** Spring Boot 3.3.11 (`@Scheduled`, `@Component`), SQLite via `sqlite-jdbc` (existing `DatabaseAdapter` static-utility pattern), Jackson `ObjectMapper` for (de)serializing `AddContentRequest`, JUnit 5.

## Global Constraints

- Migration files are numbered sequentially; the next one is `digital-me-db-6.sql` (highest existing is `digital-me-db-5.sql`).
- DAOs in this codebase are static-method classes wrapping `DatabaseAdapter` calls (see `TextEntryDao`, `SummaryCacheDao`, `McpEmbeddingDao`) — no instance state, no Spring `@Repository` annotation.
- Model classes in `com.breynisson.router.jdbc.model` are plain classes with `public final` fields and a nested `ResultSetTransform` implementing `DatabaseAdapter.ResultSetTransform<T>` (see `TextEntry`, `McpEmbedding`).
- `TIME`/timestamp columns are stored as ISO-8601 instant strings via `DatabaseAdapter.instantToTime(Instant.now())`, kept as plain `String` fields on the model (not parsed back to `Instant`) — matches `McpEmbedding.indexedAt`.
- `@Scheduled` components live in the `com.breynisson.router` package as plain `@Component` classes with constructor injection — Spring's classpath component scan (`@SpringBootApplication` on `com.breynisson.router.SpringBootApplication`) picks them up automatically; no `@Bean` method needed in `AppConfig` (see `ClaudeSessionIndexer`, which isn't registered there either).
- Tests: JUnit 5, class names end in `Test`; DB tests use a **static** `@TempDir` for the DB path, `DatabaseAdapter.setDefaultDatabasePath()` + `.init()` in `@BeforeAll`, `DatabaseAdapter.setDefaultDatabasePath(null)` in `@AfterAll`; clean up rows explicitly at the end of each test (no automatic rollback).
- Checkstyle (`mvn checkstyle:check`) must stay clean for any file this plan touches — no unused imports, no `equals()`-avoid-null violations, etc.
- Run `mvn test -Dskip.installnodenpm=true -Dskip.npm=true` (not plain `mvn test`) to skip the frontend build step, which is unrelated to this backend-only change and can fail in some environments for unrelated reasons. Use the IntelliJ-bundled Maven per `docs/tooling.md`: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd"`.

---

### Task 1: `ADD_CONTENT_QUEUE` table, model, and DAO

**Files:**
- Create: `src/main/resources/digital-me-db-6.sql`
- Create: `src/main/java/com/breynisson/router/jdbc/model/AddContentQueueEntry.java`
- Create: `src/main/java/com/breynisson/router/jdbc/AddContentQueueDao.java`
- Test: `src/test/java/com/breynisson/router/jdbc/AddContentQueueDaoTest.java`

**Interfaces:**
- Produces: `AddContentQueueEntry(String uuid, String payload, String receivedAt, int attempts)` — public final fields `uuid`, `payload`, `receivedAt`, `attempts`.
- Produces: `AddContentQueueDao.insert(String payloadJson): String` (returns the generated UUID), `AddContentQueueDao.findAllOrderedByReceivedAt(): List<AddContentQueueEntry>`, `AddContentQueueDao.delete(String uuid): void`, `AddContentQueueDao.incrementAttempts(String uuid): void`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/jdbc/AddContentQueueDaoTest.java`:

```java
package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddContentQueueDaoTest {

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

    @Test
    void insertThenFindRoundTripsPayload() {
        String uuid = AddContentQueueDao.insert("{\"source\":\"http://example.com\"}");

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();

        AddContentQueueEntry found = entries.stream().filter(e -> e.uuid.equals(uuid)).findFirst().orElseThrow();
        assertEquals("{\"source\":\"http://example.com\"}", found.payload);
        assertEquals(0, found.attempts);
        assertNotNull(found.receivedAt);

        AddContentQueueDao.delete(uuid);
    }

    @Test
    void findAllOrderedByReceivedAtReturnsEntriesInNonDecreasingOrder() {
        String uuid1 = AddContentQueueDao.insert("{\"source\":\"http://a.com\"}");
        String uuid2 = AddContentQueueDao.insert("{\"source\":\"http://b.com\"}");
        String uuid3 = AddContentQueueDao.insert("{\"source\":\"http://c.com\"}");

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();

        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).receivedAt.compareTo(entries.get(i).receivedAt) <= 0,
                    "entries must be non-decreasing by receivedAt");
        }

        AddContentQueueDao.delete(uuid1);
        AddContentQueueDao.delete(uuid2);
        AddContentQueueDao.delete(uuid3);
    }

    @Test
    void deleteRemovesOnlyTargetedEntry() {
        String uuid1 = AddContentQueueDao.insert("{\"source\":\"http://keep.com\"}");
        String uuid2 = AddContentQueueDao.insert("{\"source\":\"http://remove.com\"}");

        AddContentQueueDao.delete(uuid2);

        List<String> remainingUuids = AddContentQueueDao.findAllOrderedByReceivedAt().stream()
                .map(e -> e.uuid).toList();
        assertTrue(remainingUuids.contains(uuid1));
        assertFalse(remainingUuids.contains(uuid2));

        AddContentQueueDao.delete(uuid1);
    }

    @Test
    void incrementAttemptsIncrementsOnlyTargetedEntry() {
        String uuid1 = AddContentQueueDao.insert("{\"source\":\"http://one.com\"}");
        String uuid2 = AddContentQueueDao.insert("{\"source\":\"http://two.com\"}");

        AddContentQueueDao.incrementAttempts(uuid1);
        AddContentQueueDao.incrementAttempts(uuid1);

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();
        AddContentQueueEntry entry1 = entries.stream().filter(e -> e.uuid.equals(uuid1)).findFirst().orElseThrow();
        AddContentQueueEntry entry2 = entries.stream().filter(e -> e.uuid.equals(uuid2)).findFirst().orElseThrow();
        assertEquals(2, entry1.attempts);
        assertEquals(0, entry2.attempts);

        AddContentQueueDao.delete(uuid1);
        AddContentQueueDao.delete(uuid2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=AddContentQueueDaoTest -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: FAIL — compile error, `AddContentQueueDao`/`AddContentQueueEntry` do not exist yet.

- [ ] **Step 3: Create the migration**

Create `src/main/resources/digital-me-db-6.sql`:

```sql
CREATE TABLE IF NOT EXISTS ADD_CONTENT_QUEUE (
    UUID        VARCHAR(60) NOT NULL PRIMARY KEY,
    PAYLOAD     TEXT        NOT NULL,
    RECEIVED_AT TEXT        NOT NULL,
    ATTEMPTS    INTEGER     NOT NULL DEFAULT 0
);
```

- [ ] **Step 4: Create the model class**

Create `src/main/java/com/breynisson/router/jdbc/model/AddContentQueueEntry.java`:

```java
package com.breynisson.router.jdbc.model;

import com.breynisson.router.jdbc.DatabaseAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class AddContentQueueEntry {

    public final String uuid;
    public final String payload;
    public final String receivedAt;
    public final int attempts;

    public AddContentQueueEntry(String uuid, String payload, String receivedAt, int attempts) {
        this.uuid = uuid;
        this.payload = payload;
        this.receivedAt = receivedAt;
        this.attempts = attempts;
    }

    public static class ResultSetTransform implements DatabaseAdapter.ResultSetTransform<AddContentQueueEntry> {

        @Override
        public List<AddContentQueueEntry> transform(ResultSet rset) throws SQLException {
            List<AddContentQueueEntry> list = new ArrayList<>();
            while (rset.next()) {
                list.add(new AddContentQueueEntry(
                        rset.getString(1),  // UUID
                        rset.getString(2),  // PAYLOAD
                        rset.getString(3),  // RECEIVED_AT
                        rset.getInt(4)));   // ATTEMPTS
            }
            return list;
        }
    }
}
```

- [ ] **Step 5: Create the DAO**

Create `src/main/java/com/breynisson/router/jdbc/AddContentQueueDao.java`:

```java
package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.AddContentQueueEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AddContentQueueDao {

    private static final String TABLE_NAME = "ADD_CONTENT_QUEUE";

    public static String insert(String payloadJson) {
        String uuid = UUID.randomUUID().toString();
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + TABLE_NAME + " (UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS) VALUES (?, ?, ?, 0)",
                uuid, payloadJson, DatabaseAdapter.instantToTime(Instant.now()));
        return uuid;
    }

    public static List<AddContentQueueEntry> findAllOrderedByReceivedAt() {
        return DatabaseAdapter.selectList(
                "SELECT UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS FROM " + TABLE_NAME + " ORDER BY RECEIVED_AT ASC",
                new AddContentQueueEntry.ResultSetTransform());
    }

    public static void delete(String uuid) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE_NAME + " WHERE UUID=?", uuid);
    }

    public static void incrementAttempts(String uuid) {
        DatabaseAdapter.runPreparedStatement(
                "UPDATE " + TABLE_NAME + " SET ATTEMPTS = ATTEMPTS + 1 WHERE UUID=?", uuid);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=AddContentQueueDaoTest -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: Run checkstyle on the new files**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dskip.installnodenpm=true -Dskip.npm=true checkstyle:check`
Expected: no new violations attributed to `AddContentQueueDao.java`, `AddContentQueueEntry.java`, or `AddContentQueueDaoTest.java` (the project has 8 pre-existing violations in unrelated vendored/test files — confirm none of the reported errors are in these three new files).

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/digital-me-db-6.sql src/main/java/com/breynisson/router/jdbc/model/AddContentQueueEntry.java src/main/java/com/breynisson/router/jdbc/AddContentQueueDao.java src/test/java/com/breynisson/router/jdbc/AddContentQueueDaoTest.java
git commit -m "Add ADD_CONTENT_QUEUE table, model, and DAO"
```

---

### Task 2: `AddContentQueueProcessor` (scheduled queue drain)

**Files:**
- Create: `src/main/java/com/breynisson/router/AddContentQueueProcessor.java`
- Test: `src/test/java/com/breynisson/router/AddContentQueueProcessorTest.java`

**Interfaces:**
- Consumes: `AddContentQueueDao.findAllOrderedByReceivedAt()`, `.delete(String)`, `.incrementAttempts(String)` (Task 1); `DigitalMeStorage.addContent(AddContentRequest): AddContentResponse` (existing, unchanged); `AddContentRequest` (existing, Jackson-serializable bean with `source`/`name`/`content` getters+setters).
- Produces: `AddContentQueueProcessor(DigitalMeStorage storage)` constructor; `public void processPending()` — package-visibility-compatible public method so tests can invoke it directly without waiting for the `@Scheduled` trigger.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/AddContentQueueProcessorTest.java`:

```java
package com.breynisson.router;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.digitalme.DefaultDigitalMeStorage;
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddContentQueueProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path dbDir;

    @TempDir
    static Path dataDir;

    @TempDir
    Path indexDir;

    private AddContentQueueProcessor processor;
    private DefaultDigitalMeStorage storage;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    @BeforeEach
    void setUp() {
        LuceneIndex.setIndexPath(indexDir.toString());
        LuceneIndex.deleteIndex();
        storage = new DefaultDigitalMeStorage(dataDir.toString(), new EmbeddingIndex(text -> null, dataDir.toString()));
        processor = new AddContentQueueProcessor(storage);
    }

    @AfterEach
    void tearDown() {
        LuceneIndex.deleteIndex();
    }

    private void cleanupDb(String source) {
        TextEntryDao.findByName(source).forEach(e -> TextEntryDao.delete(e.uuid));
    }

    private static String payloadFor(AddContentRequest request) throws Exception {
        return MAPPER.writeValueAsString(request);
    }

    @Test
    void processPendingIndexesQueuedEntryAndRemovesIt() throws Exception {
        AddContentRequest request = AddContentRequests.of("http://queued.com", "Queued Page", "queued searchable content");
        String uuid = AddContentQueueDao.insert(payloadFor(request));

        processor.processPending();

        assertEquals(1, storage.search("queued").results().size());
        assertTrue(AddContentQueueDao.findAllOrderedByReceivedAt().stream().noneMatch(e -> e.uuid.equals(uuid)));

        cleanupDb("http://queued.com");
    }

    @Test
    void processPendingSurvivesAcrossSimulatedRestart() throws Exception {
        // A row inserted but never processed (as if the JVM died before the first scheduled
        // tick) must still be there and get picked up by a fresh processPending() call.
        AddContentRequest request = AddContentRequests.of("http://survives-restart.com", "Page", "content surviving a crash");
        String uuid = AddContentQueueDao.insert(payloadFor(request));

        AddContentQueueProcessor freshProcessor = new AddContentQueueProcessor(storage);
        freshProcessor.processPending();

        assertEquals(1, storage.search("surviving").results().size());
        assertTrue(AddContentQueueDao.findAllOrderedByReceivedAt().stream().noneMatch(e -> e.uuid.equals(uuid)));

        cleanupDb("http://survives-restart.com");
    }

    @Test
    void poisonPillPayloadIsDroppedAfterMaxAttempts() {
        String uuid = AddContentQueueDao.insert("not valid json");

        for (int i = 0; i < 5; i++) {
            processor.processPending();
        }

        assertTrue(AddContentQueueDao.findAllOrderedByReceivedAt().stream().noneMatch(e -> e.uuid.equals(uuid)),
                "entry should be dropped after MAX_ATTEMPTS failed attempts");
    }

    @Test
    void poisonPillPayloadIncrementsAttemptsBeforeMaxAttemptsReached() {
        String uuid = AddContentQueueDao.insert("not valid json");

        processor.processPending();

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();
        AddContentQueueEntry entry = entries.stream().filter(e -> e.uuid.equals(uuid)).findFirst().orElseThrow();
        assertEquals(1, entry.attempts);

        AddContentQueueDao.delete(uuid);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=AddContentQueueProcessorTest -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: FAIL — compile error, `AddContentQueueProcessor` does not exist yet.

- [ ] **Step 3: Create the processor**

Create `src/main/java/com/breynisson/router/AddContentQueueProcessor.java`:

```java
package com.breynisson.router;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AddContentQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(AddContentQueueProcessor.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DigitalMeStorage storage;

    public AddContentQueueProcessor(DigitalMeStorage storage) {
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 2000)
    public void processPending() {
        for (AddContentQueueEntry entry : AddContentQueueDao.findAllOrderedByReceivedAt()) {
            processEntry(entry);
        }
    }

    private void processEntry(AddContentQueueEntry entry) {
        try {
            AddContentRequest request = MAPPER.readValue(entry.payload, AddContentRequest.class);
            storage.addContent(request);
            AddContentQueueDao.delete(entry.uuid);
        } catch (Exception e) {
            handleFailure(entry, e);
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

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=AddContentQueueProcessorTest -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Run checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dskip.installnodenpm=true -Dskip.npm=true checkstyle:check`
Expected: no new violations attributed to `AddContentQueueProcessor.java` or `AddContentQueueProcessorTest.java`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/breynisson/router/AddContentQueueProcessor.java src/test/java/com/breynisson/router/AddContentQueueProcessorTest.java
git commit -m "Add scheduled AddContentQueueProcessor to drain the addContent queue"
```

---

### Task 3: Wire `IndexPage.addContent()` to the queue

**Files:**
- Modify: `src/main/java/com/breynisson/router/ui/IndexPage.java:126-129`
- Modify: `src/test/java/com/breynisson/router/ui/IndexPageTest.java`

**Interfaces:**
- Consumes: `AddContentQueueDao.insert(String): String` (Task 1); existing `AddContentRequest`/`AddContentResponse` beans (unchanged shape — no client-visible contract change).

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/breynisson/router/ui/IndexPageTest.java`, add these imports (the class already imports `com.breynisson.router.digitalme.AddContentRequest`, `AddContentRequests`, `AddContentResponse` — add the two new ones and `java.util.List`):

```java
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
```

and

```java
import java.util.List;
```

Replace the existing `addContentDelegatesToStorage` test:

```java
    @Test
    void addContentDelegatesToStorage() {
        AddContentResponse response = indexPage.addContent(request("http://example.com", "Example", "some content"));

        assertTrue(response.isSuccess());
        assertEquals(1, indexPage.search("some content").results().size());
    }
```

with:

```java
    @Test
    void addContentQueuesRequestInsteadOfProcessingSynchronously() throws Exception {
        AddContentResponse response = indexPage.addContent(request("http://example.com", "Example", "some content"));

        assertTrue(response.isSuccess());
        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();
        AddContentQueueEntry entry = entries.stream()
                .filter(e -> e.payload.contains("http://example.com"))
                .findFirst().orElseThrow();
        assertTrue(entry.payload.contains("some content"));
        // Not processed synchronously: TestDigitalMeStorage never saw this request, so it's not searchable yet.
        assertTrue(indexPage.search("some content").results().isEmpty());

        AddContentQueueDao.delete(entry.uuid);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=IndexPageTest -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: FAIL — `addContentQueuesRequestInsteadOfProcessingSynchronously` fails because `IndexPage.addContent()` still calls `storage.addContent()` directly, so no row lands in `ADD_CONTENT_QUEUE` (the `.orElseThrow()` on the empty stream throws `NoSuchElementException`).

- [ ] **Step 3: Update `IndexPage.addContent()`**

In `src/main/java/com/breynisson/router/ui/IndexPage.java`, add these imports alongside the existing ones:

```java
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
```

Add a static field next to the existing `log` field:

```java
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
```

Replace:

```java
    @PostMapping(value="/addContent", consumes = "application/json", produces = "application/json")
    public AddContentResponse addContent(@RequestBody AddContentRequest addContentRequest) {
        return storage.addContent(addContentRequest);
    }
```

with:

```java
    @PostMapping(value="/addContent", consumes = "application/json", produces = "application/json")
    public AddContentResponse addContent(@RequestBody AddContentRequest addContentRequest) throws JsonProcessingException {
        AddContentQueueDao.insert(OBJECT_MAPPER.writeValueAsString(addContentRequest));
        AddContentResponse response = new AddContentResponse();
        response.setSuccess(true);
        return response;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=IndexPageTest -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: all `IndexPageTest` tests pass, including `addContentQueuesRequestInsteadOfProcessingSynchronously`.

- [ ] **Step 5: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -Dskip.installnodenpm=true -Dskip.npm=true test`
Expected: `BUILD SUCCESS`, all tests passing (this also exercises `SpringBootApplicationTest`, which boots the full Spring context and would fail if `AddContentQueueProcessor` or the new migration had a wiring problem).

- [ ] **Step 6: Run checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dskip.installnodenpm=true -Dskip.npm=true checkstyle:check`
Expected: no new violations attributed to `IndexPage.java` or `IndexPageTest.java`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/breynisson/router/ui/IndexPage.java src/test/java/com/breynisson/router/ui/IndexPageTest.java
git commit -m "Queue /addContent HTTP submissions instead of processing them synchronously"
```

---

### Task 4: Update `docs/architecture.md`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:** None (documentation only).

- [ ] **Step 1: Update the REST API table row**

Replace:

```markdown
| `POST` | `/addContent` | Indexes content; body: `{ source, name, content }` |
```

with:

```markdown
| `POST` | `/addContent` | Durably queues content for async indexing; body: `{ source, name, content }`; returns `{ success: true }` as soon as the raw request is persisted to `ADD_CONTENT_QUEUE` — not once indexing has actually completed |
```

- [ ] **Step 2: Prepend the queue-then-poll explanation**

The paragraph immediately after that table currently starts with:

```markdown
`/addContent` uses a `ReentrantLock` for thread safety around the synchronous write path (Lucene, `TEXT_ENTRY`, mcp-resources file). The embedding step (`embeddingIndex.indexFile()`) runs outside that lock on a dedicated bounded worker pool (`embedding.executor.pool-size`, default 1) instead of an unbounded `CompletableFuture.runAsync` — a burst of submissions (fast browsing, several screenshot-session flushes) queues up and drains one (or `pool-size` many) at a time rather than firing unbounded concurrent Ollama calls. Before anything else, `LocalFileEndpoint.isLocalFileUrl()` checks whether `source` is this app's own `/localFile?...` rendering endpoint
```

Prepend a new paragraph before it (keep everything from `Before anything else,` onward unchanged — it still accurately describes `DigitalMeStorage.addContent()`'s own behavior, just invoked from a different caller now):

```markdown
HTTP submissions to `/addContent` (Chrome extension, screenshot-OCR pipeline) are durably queued rather than processed inline: `IndexPage.addContent()` serializes the request to JSON, inserts it into the `ADD_CONTENT_QUEUE` table via `AddContentQueueDao`, and returns success immediately — the only durable step in the request path is that single SQLite insert, so a crash mid-request can lose at most one not-yet-queued submission rather than one mid-pipeline submission. `AddContentQueueProcessor` (`@Scheduled(fixedDelay = 2000)`) polls the queue and, for each entry, deserializes the payload and calls `DigitalMeStorage.addContent()` (the pipeline described below), deleting the row only after that call returns — so a crash mid-processing leaves the row for the next poll (after restart) to retry, the same principle `FileChangeWatcher` already relies on for its own re-scans. A payload that fails outright (e.g. corrupt JSON) increments an `ATTEMPTS` counter and is dropped with a logged error after 5 failed attempts rather than retried forever. `FileChangeWatcher` is unaffected by any of this — it still calls `DigitalMeStorage.addContent()` directly, in-process, since it's already self-healing via its own mtime-vs-`TEXT_ENTRY.TIME` comparison, independent of `addContent()`'s internals.

`DigitalMeStorage.addContent()` uses a `ReentrantLock` for thread safety around the synchronous write path (Lucene, `TEXT_ENTRY`, mcp-resources file). The embedding step (`embeddingIndex.indexFile()`) runs outside that lock on a dedicated bounded worker pool (`embedding.executor.pool-size`, default 1) instead of an unbounded `CompletableFuture.runAsync` — a burst of submissions (fast browsing, several screenshot-session flushes) queues up and drains one (or `pool-size` many) at a time rather than firing unbounded concurrent Ollama calls. Before anything else, `LocalFileEndpoint.isLocalFileUrl()` checks whether `source` is this app's own `/localFile?...` rendering endpoint
```

- [ ] **Step 3: Add DAO/processor bullets to Key Subsystems**

Immediately after the existing `### TextEntryDao` bullet block (which ends with `- findByName(source) is used to check if an entry exists before insert vs. update`) and before `### EmbeddingClient (functional interface)`, insert:

```markdown
### `AddContentQueueDao`
- `insert(payloadJson)` — inserts a new row with a generated UUID and `RECEIVED_AT` set to now, returns the UUID
- `findAllOrderedByReceivedAt()` — returns all pending entries oldest-first
- `delete(uuid)` — removes a processed (or dropped) entry
- `incrementAttempts(uuid)` — bumps the failure counter for a poison-pill payload

### `AddContentQueueProcessor`
- `@Scheduled(fixedDelay = 2000)` — polls `ADD_CONTENT_QUEUE`, deserializes each entry's JSON payload back into an `AddContentRequest`, and calls `DigitalMeStorage.addContent()`
- Deletes the row only after `addContent()` returns, so a crash mid-processing leaves it for the next poll (including after a restart) to retry
- A payload that fails to deserialize or otherwise throws increments `ATTEMPTS` via `AddContentQueueDao`; after 5 failed attempts the entry is logged at `ERROR` and dropped rather than retried forever
```

- [ ] **Step 4: Add the new table to the Database Schema section**

Replace:

```markdown
SUMMARY_CACHE (SOURCE_URL PK, SUMMARY, CREATED_AT)  -- cached on-demand summaries, discarded when a source's content is replaced
```

with:

```markdown
SUMMARY_CACHE (SOURCE_URL PK, SUMMARY, CREATED_AT)  -- cached on-demand summaries, discarded when a source's content is replaced
ADD_CONTENT_QUEUE (UUID PK, PAYLOAD, RECEIVED_AT, ATTEMPTS)  -- durable queue for HTTP /addContent submissions, drained by AddContentQueueProcessor
```

- [ ] **Step 5: Commit**

```bash
git add docs/architecture.md
git commit -m "Document the durable /addContent queue in architecture.md"
```
