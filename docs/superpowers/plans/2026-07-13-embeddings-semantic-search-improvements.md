# Embeddings & Semantic Search Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix P1, P2, P3(low), P4, P5, P6, P7 from `docs/reviews/2026-07-13-embeddings-semantic-search-improvements.md` — chunk long documents instead of truncating them, use nomic task prefixes, cache unit-normalized vectors for fast dot-product scoring, apply a score threshold, dedup/limit results correctly, and keep the embedding table clean and model-scoped.

**Architecture:** A new `Chunker` utility splits document bodies into ~2000-char, sentence-boundary-snapped windows. `MCP_EMBEDDING` becomes chunk-keyed `(FILE_PATH, CHUNK_INDEX)` with `CHUNK_TEXT` and `MODEL` columns (schema wipe-and-rebuild via `digital-me-db-4.sql`). `EmbeddingIndex` embeds/searches at the chunk level, keeps an in-memory unit-normalized vector cache, reconciles stale rows on startup, and dedups search results to one (best-scoring) chunk per file. `SemanticSearch` builds snippets directly from the winning chunk's text instead of re-reading files.

**Tech Stack:** Java 19, Spring Boot 3.3.11, SQLite (`sqlite-jdbc`), JUnit 5. No new dependencies.

## Global Constraints

- Chunk target size: 2000 chars; sentence-boundary lookback: 500 chars (from spec §2)
- Score threshold default: `semantic-search.min-score=0.5` (from spec §5)
- Final result cap: `FINAL_RESULT_LIMIT = 50` distinct files (from spec §5)
- Config prefix values carry **no** trailing space; code joins `prefix + " " + text` itself (from spec §3)
- Schema migration is wipe-and-rebuild — `DROP TABLE IF EXISTS MCP_EMBEDDING` then recreate (from spec §1)
- Maven must be invoked via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" <goals>` (`docs/tooling.md`)
- Run `/simplify` after changing source files (project `CLAUDE.md`)
- Git feature branches use prefix `feature/`; update `CLAUDE.md`/docs when committing a feature branch (user's global `CLAUDE.md`)

---

### Task 1: Feature branch + `Chunker`

**Files:**
- Create: `src/main/java/com/breynisson/router/mcp/Chunker.java`
- Test: `src/test/java/com/breynisson/router/mcp/ChunkerTest.java`

**Interfaces:**
- Produces: `Chunker.chunk(String text) -> List<String>` (package-private, `com.breynisson.router.mcp`), `Chunker.TARGET_CHUNK_CHARS` (package-private `static final int`, value `2000`), `Chunker.BOUNDARY_LOOKBACK_CHARS` (package-private `static final int`, value `500`). Consumed by Task 4 (`EmbeddingIndex`).

- [ ] **Step 1: Create the feature branch**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" checkout -b feature/embeddings_and_semantic_search_improvements
```

- [ ] **Step 2: Write the failing tests**

```java
package com.breynisson.router.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {

    private static String sentence(int n) {
        return "This is sentence number " + n + " in the test document, added to pad out the length nicely.";
    }

    private static String repeatedSentences(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(sentence(i)).append(" ");
        }
        return sb.toString().strip();
    }

    @Test
    void shortTextProducesSingleChunk() {
        String text = "Just one short sentence.";
        List<String> chunks = Chunker.chunk(text);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void longTextSplitsOnSentenceBoundaries() {
        String text = repeatedSentences(60); // ~90 chars/sentence, ~5400 chars total
        List<String> chunks = Chunker.chunk(text);

        assertTrue(chunks.size() > 1, "Expected multiple chunks for long text");
        for (int i = 0; i < chunks.size() - 1; i++) {
            String chunk = chunks.get(i);
            assertTrue(chunk.endsWith("."), "Non-final chunk should end at a sentence boundary: [" + chunk + "]");
            assertTrue(chunk.length() <= Chunker.TARGET_CHUNK_CHARS,
                    "Chunk should not exceed the target size: length=" + chunk.length());
        }
    }

    @Test
    void chunksReconstructOriginalTextExactly() {
        String text = repeatedSentences(60);
        List<String> chunks = Chunker.chunk(text);
        assertTrue(chunks.size() > 1);

        StringBuilder reconstructed = new StringBuilder();
        for (String chunk : chunks) reconstructed.append(chunk);

        assertEquals(text, reconstructed.toString(),
                "Chunks should partition the text cleanly at sentence boundaries with no duplicated or dropped characters");
    }

    @Test
    void noSentenceBoundaryFallsBackToHardCut() {
        String text = "x".repeat(5000); // no punctuation anywhere
        List<String> chunks = Chunker.chunk(text);

        assertTrue(chunks.size() > 1);
        assertEquals(Chunker.TARGET_CHUNK_CHARS, chunks.get(0).length(),
                "With no sentence boundary available, chunk should hard-cut at the target size");
        assertEquals(Chunker.TARGET_CHUNK_CHARS, chunks.get(1).length());
    }

    @Test
    void finalChunkCanBeShorterThanTarget() {
        String text = repeatedSentences(25);
        List<String> chunks = Chunker.chunk(text);
        String last = chunks.get(chunks.size() - 1);
        assertFalse(last.isEmpty());
        assertTrue(last.length() <= Chunker.TARGET_CHUNK_CHARS);
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(Chunker.chunk("").isEmpty());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail (class doesn't exist yet)**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=ChunkerTest`
Expected: COMPILE ERROR / FAIL — `Chunker` does not exist

- [ ] **Step 4: Implement `Chunker`**

```java
package com.breynisson.router.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into ~{@link #TARGET_CHUNK_CHARS}-char windows, snapping each chunk's end
 * back to the nearest full sentence within {@link #BOUNDARY_LOOKBACK_CHARS} chars, instead of
 * cutting a sentence in half. The next chunk starts at that same boundary, so the deferred
 * sentence becomes the first sentence of the next chunk — chunks partition the text cleanly with
 * no duplicated or dropped characters.
 */
class Chunker {

    static final int TARGET_CHUNK_CHARS = 2000;
    static final int BOUNDARY_LOOKBACK_CHARS = 500;

    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int naturalEnd = Math.min(start + TARGET_CHUNK_CHARS, len);
            int end = naturalEnd;
            if (naturalEnd < len) {
                int boundary = lastSentenceBoundary(text, start, naturalEnd);
                if (boundary > start) {
                    end = boundary;
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    /** Returns the index just after the last sentence-ending punctuation before naturalEnd, or -1 if none found. */
    private static int lastSentenceBoundary(String text, int start, int naturalEnd) {
        int lookbackStart = Math.max(start, naturalEnd - BOUNDARY_LOOKBACK_CHARS);
        for (int i = naturalEnd - 1; i >= lookbackStart; i--) {
            char c = text.charAt(i);
            boolean isSentenceEnd = c == '.' || c == '!' || c == '?';
            if (isSentenceEnd && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=ChunkerTest`
Expected: PASS (6 tests)

- [ ] **Step 6: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add src/main/java/com/breynisson/router/mcp/Chunker.java src/test/java/com/breynisson/router/mcp/ChunkerTest.java
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: add sentence-boundary-aware document chunker"
```

---

### Task 2: Schema migration + `McpEmbedding` model + `McpEmbeddingDao`

**Files:**
- Create: `src/main/resources/digital-me-db-4.sql`
- Modify: `src/main/java/com/breynisson/router/jdbc/model/McpEmbedding.java`
- Modify: `src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java`
- Modify: `src/test/java/com/breynisson/router/jdbc/McpEmbeddingDaoTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `McpEmbedding(String filePath, int chunkIndex, String sourceUrl, String chunkText, byte[] embedding, String model, String indexedAt)` constructor; fields `filePath, chunkIndex, sourceUrl, chunkText, embedding, model, indexedAt` all `public final`. `McpEmbeddingDao.findAllFilePaths() -> Set<String>` (distinct), `McpEmbeddingDao.findAll() -> List<McpEmbedding>` (model/indexedAt come back `null`), `McpEmbeddingDao.upsert(McpEmbedding)`, `McpEmbeddingDao.deleteByFilePath(String)`, `McpEmbeddingDao.deleteByModelNot(String currentModel)` (new). Consumed by Task 4 (`EmbeddingIndex`).

- [ ] **Step 1: Write the failing tests (full replacement of `McpEmbeddingDaoTest`)**

```java
package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpEmbeddingDaoTest {

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

    private static byte[] embeddingBytes(float... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES);
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    private static McpEmbedding embedding(String path, int chunkIndex, String sourceUrl, String chunkText, String model) {
        return new McpEmbedding(path, chunkIndex, sourceUrl, chunkText, embeddingBytes(1.0f, 2.0f), model, "2026-01-01T00:00:00Z");
    }

    private static void cleanup(String filePath) {
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH='" + filePath + "'");
    }

    @Test
    void upsertAndFindAll() {
        String path = "/tmp/dao-test-1.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/1", "chunk text", "nomic-embed-text"));

        List<McpEmbedding> all = McpEmbeddingDao.findAll();
        assertTrue(all.stream().anyMatch(e -> e.filePath.equals(path) && e.sourceUrl.equals("http://example.com/1")));
        cleanup(path);
    }

    @Test
    void findAllFilePathsReturnsDistinctPathAcrossChunks() {
        String path = "/tmp/dao-test-2.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/2", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/2", "chunk 1", "nomic-embed-text"));

        Set<String> paths = McpEmbeddingDao.findAllFilePaths();
        assertTrue(paths.contains(path));
        assertEquals(1, paths.stream().filter(p -> p.equals(path)).count());
        cleanup(path);
    }

    @Test
    void upsertReplacesSameChunkIndex() {
        String path = "/tmp/dao-test-3.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://old.com", "old text", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 0, "http://new.com", "new text", "nomic-embed-text"));

        List<McpEmbedding> matching = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(path)).toList();
        assertEquals(1, matching.size());
        assertEquals("http://new.com", matching.get(0).sourceUrl);
        cleanup(path);
    }

    @Test
    void differentChunkIndexesForSameFileAreSeparateRows() {
        String path = "/tmp/dao-test-5.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/5", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/5", "chunk 1", "nomic-embed-text"));

        List<McpEmbedding> matching = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(path)).toList();
        assertEquals(2, matching.size());
        cleanup(path);
    }

    @Test
    void findAllEmbeddingBytesRoundTrip() {
        String path = "/tmp/dao-test-4.txt";
        float[] original = {0.1f, 0.5f, -0.3f};
        McpEmbeddingDao.upsert(new McpEmbedding(path, 0, "http://example.com/4", "chunk text",
                embeddingBytes(original), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        McpEmbedding stored = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(path)).findFirst().orElseThrow();
        ByteBuffer buf = ByteBuffer.wrap(stored.embedding);
        assertArrayEquals(original, new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat()}, 0.0001f);
        cleanup(path);
    }

    @Test
    void deleteByFilePathRemovesAllChunksForFile() {
        String path = "/tmp/dao-test-6.txt";
        McpEmbeddingDao.upsert(embedding(path, 0, "http://example.com/6", "chunk 0", "nomic-embed-text"));
        McpEmbeddingDao.upsert(embedding(path, 1, "http://example.com/6", "chunk 1", "nomic-embed-text"));

        McpEmbeddingDao.deleteByFilePath(path);

        assertTrue(McpEmbeddingDao.findAll().stream().noneMatch(e -> e.filePath.equals(path)));
    }

    @Test
    void deleteByModelNotRemovesOnlyMismatchedRows() {
        String keepPath = "/tmp/dao-test-7-keep.txt";
        String dropPath = "/tmp/dao-test-7-drop.txt";
        McpEmbeddingDao.upsert(embedding(keepPath, 0, "http://keep.com", "chunk", "current-model"));
        McpEmbeddingDao.upsert(embedding(dropPath, 0, "http://drop.com", "chunk", "old-model"));

        McpEmbeddingDao.deleteByModelNot("current-model");

        Set<String> remaining = McpEmbeddingDao.findAllFilePaths();
        assertTrue(remaining.contains(keepPath));
        assertFalse(remaining.contains(dropPath));
        cleanup(keepPath);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (old constructor/columns don't match)**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=McpEmbeddingDaoTest`
Expected: COMPILE ERROR — `McpEmbedding` constructor doesn't accept 7 args; `deleteByModelNot` doesn't exist

- [ ] **Step 3: Create the schema migration**

```sql
DROP TABLE IF EXISTS MCP_EMBEDDING;

CREATE TABLE MCP_EMBEDDING (
    FILE_PATH   TEXT NOT NULL,
    CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
    SOURCE_URL  TEXT NOT NULL,
    CHUNK_TEXT  TEXT NOT NULL,
    EMBEDDING   BLOB NOT NULL,
    MODEL       TEXT NOT NULL,
    INDEXED_AT  TEXT NOT NULL,
    PRIMARY KEY (FILE_PATH, CHUNK_INDEX)
);
```

- [ ] **Step 4: Rewrite `McpEmbedding`**

```java
package com.breynisson.router.jdbc.model;

import com.breynisson.router.jdbc.DatabaseAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class McpEmbedding {

    public final String filePath;
    public final int chunkIndex;
    public final String sourceUrl;
    public final String chunkText;
    public final byte[] embedding;
    public final String model;
    public final String indexedAt;

    public McpEmbedding(String filePath, int chunkIndex, String sourceUrl, String chunkText,
                         byte[] embedding, String model, String indexedAt) {
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
                list.add(new McpEmbedding(
                        rset.getString(1),   // FILE_PATH
                        rset.getInt(2),       // CHUNK_INDEX
                        rset.getString(3),   // SOURCE_URL
                        rset.getString(4),   // CHUNK_TEXT
                        rset.getBytes(5),    // EMBEDDING
                        null,                // MODEL not needed for search
                        null));              // INDEXED_AT not needed for search
            }
            return list;
        }
    }
}
```

- [ ] **Step 5: Rewrite `McpEmbeddingDao`**

```java
package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.McpEmbedding;

import java.util.List;
import java.util.Set;

public class McpEmbeddingDao {

    private static final String TABLE = "MCP_EMBEDDING";
    private static final McpEmbedding.ResultSetTransform TRANSFORM = new McpEmbedding.ResultSetTransform();

    public static Set<String> findAllFilePaths() {
        return Set.copyOf(DatabaseAdapter.selectList(
                "SELECT DISTINCT FILE_PATH FROM " + TABLE,
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM));
    }

    public static List<McpEmbedding> findAll() {
        return DatabaseAdapter.selectList(
                "SELECT FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING FROM " + TABLE,
                TRANSFORM);
    }

    public static void upsert(McpEmbedding embedding) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT OR REPLACE INTO " + TABLE
                        + " (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING, MODEL, INDEXED_AT) VALUES (?, ?, ?, ?, ?, ?, ?)",
                embedding.filePath, embedding.chunkIndex, embedding.sourceUrl, embedding.chunkText,
                embedding.embedding, embedding.model, embedding.indexedAt);
    }

    public static void deleteByFilePath(String filePath) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE FILE_PATH = ?", filePath);
    }

    public static void deleteByModelNot(String currentModel) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE MODEL <> ?", currentModel);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=McpEmbeddingDaoTest`
Expected: PASS (7 tests)

- [ ] **Step 7: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add src/main/resources/digital-me-db-4.sql src/main/java/com/breynisson/router/jdbc/model/McpEmbedding.java src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java src/test/java/com/breynisson/router/jdbc/McpEmbeddingDaoTest.java
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: chunk-key MCP_EMBEDDING schema with model tracking"
```

---

### Task 3: `EmbeddingIndex` — chunked indexing, prefixes, cache, threshold, dedup, reconciliation

**Files:**
- Modify: `src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java`
- Modify: `src/test/java/com/breynisson/router/mcp/EmbeddingIndexTest.java`

**Interfaces:**
- Consumes: `Chunker.chunk(String) -> List<String>` (Task 1); `McpEmbedding` 7-arg constructor, `McpEmbeddingDao.findAllFilePaths/findAll/upsert/deleteByFilePath/deleteByModelNot` (Task 2).
- Produces: `EmbeddingIndex(EmbeddingClient, String dataDir, String model, String documentPrefix, String queryPrefix, float minScore)` (primary, `@Autowired`); `EmbeddingIndex(EmbeddingClient, String dataDir)` (public test convenience — must be `public`, not package-private, since `FileChangeWatcherTest` and `DefaultDigitalMeStorageTest` construct it from other packages: model=`"nomic-embed-text"`, prefixes=`""`, minScore=`0f`); `void indexAll()`; `void indexFile(Path)`; `List<ScoredResult> findSimilar(String query, int topK)`; `record ScoredResult(String filePath, String sourceUrl, float score, String chunkText)`. Consumed by Task 4 (`SemanticSearch`) and existing `McpServerConfig`/`FileChangeWatcherTest`/`DefaultDigitalMeStorageTest`/tests (constructor signature preserved via the convenience overload, so those call sites need no changes).

- [ ] **Step 1: Replace the truncation test and add new failing tests in `EmbeddingIndexTest`**

Replace the existing `indexFileTruncatesContentBeyond4000Chars` test with the following, and add the new tests below it (keep all other existing tests in the file as-is — `indexFileStoresEmbedding`, `indexFileSkipsWhenOllamaUnavailable`, `indexAllSkipsAlreadyIndexedFiles`, `findSimilarRanksCloserResultFirst`, `findSimilarReturnsEmptyWhenOllamaUnavailable` all keep working unchanged against the new implementation because of the convenience constructor):

```java
    @Test
    void indexFileChunksLongContentIntoMultipleEmbedCalls() throws Exception {
        Path file = dataDir.resolve("large.txt");
        String longBody = "Sentence. ".repeat(500); // ~5000 chars
        Files.writeString(file, "http://example.com\n" + longBody);

        List<String> embedded = new java.util.ArrayList<>();
        EmbeddingIndex index = new EmbeddingIndex(text -> {
            embedded.add(text);
            return new float[]{1.0f};
        }, dataDir.toString());

        index.indexFile(file);

        assertTrue(embedded.size() > 1, "Expected multiple chunks to be embedded for long content");
        for (String text : embedded) {
            assertTrue(text.length() <= Chunker.TARGET_CHUNK_CHARS,
                    "Each embedded chunk should not exceed the target chunk size");
        }
        long storedRows = McpEmbeddingDao.findAll().stream()
                .filter(e -> e.filePath.equals(file.toAbsolutePath().toString())).count();
        assertEquals(embedded.size(), storedRows);
        cleanup(file);
    }

    @Test
    void findSimilarAppliesScoreThreshold() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path fileA = dir.resolve("a.txt");
        Path fileB = dir.resolve("b.txt");
        Files.writeString(fileA, "http://a.com\nrelevant content");
        Files.writeString(fileB, "http://b.com\nirrelevant content");

        EmbeddingIndex index = new EmbeddingIndex(
                text -> {
                    // Check "irrelevant" first: "irrelevant content".contains("relevant content") is
                    // also true (it's a substring), so the more specific check must come first.
                    if (text.contains("irrelevant content")) return new float[]{0.0f, 1.0f};
                    if (text.contains("relevant content")) return new float[]{1.0f, 0.0f};
                    return new float[]{1.0f, 0.0f}; // query
                },
                dataDir.toString(), "nomic-embed-text", "", "", 0.5f);

        index.indexFile(fileA);
        index.indexFile(fileB);

        List<EmbeddingIndex.ScoredResult> results = index.findSimilar("query", 10);
        assertEquals(1, results.size());
        assertEquals("http://a.com", results.get(0).sourceUrl());
        cleanup(fileA, fileB);
    }

    @Test
    void findSimilarDedupsToBestChunkPerFile() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("multi.txt");
        String longBody = "Weak match sentence. ".repeat(150) + "Strong match sentence. ".repeat(150);
        Files.writeString(file, "http://multi.com\n" + longBody);

        EmbeddingIndex index = new EmbeddingIndex(
                text -> {
                    if (text.contains("Strong match")) return new float[]{1.0f, 0.0f};
                    if (text.contains("Weak match")) return new float[]{0.0f, 1.0f};
                    return new float[]{1.0f, 0.0f}; // query
                },
                dataDir.toString(), "nomic-embed-text", "", "", 0f);

        index.indexFile(file);
        List<EmbeddingIndex.ScoredResult> results = index.findSimilar("query", 10);

        assertEquals(1, results.size(), "Multiple chunks of the same file should collapse to one result");
        assertEquals("http://multi.com", results.get(0).sourceUrl());
        assertEquals(1.0f, results.get(0).score(), 0.001f);
        cleanup(file);
    }

    @Test
    void indexAllRemovesRowsForDeletedFiles() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("temp.txt");
        Files.writeString(file, "http://temp.com\ncontent");

        EmbeddingIndex index = new EmbeddingIndex(text -> new float[]{1.0f}, dataDir.toString());
        index.indexAll();
        assertTrue(McpEmbeddingDao.findAllFilePaths().contains(file.toAbsolutePath().toString()));

        Files.delete(file);
        index.indexAll();

        assertFalse(McpEmbeddingDao.findAllFilePaths().contains(file.toAbsolutePath().toString()));
        cleanup(file);
    }

    @Test
    void indexAllReembedsFilesWhenModelChanges() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("model-change.txt");
        Files.writeString(file, "http://model-change.com\ncontent");

        AtomicInteger callCount = new AtomicInteger();
        EmbeddingIndex oldModelIndex = new EmbeddingIndex(
                text -> { callCount.incrementAndGet(); return new float[]{1.0f}; },
                dataDir.toString(), "old-model", "", "", 0f);
        oldModelIndex.indexAll();
        assertEquals(1, callCount.get());

        EmbeddingIndex newModelIndex = new EmbeddingIndex(
                text -> { callCount.incrementAndGet(); return new float[]{1.0f}; },
                dataDir.toString(), "new-model", "", "", 0f);
        newModelIndex.indexAll();

        assertEquals(2, callCount.get(), "File should be re-embedded once the configured model changes");
        cleanup(file);
    }

    @Test
    void indexFileAppliesDocumentPrefix() throws Exception {
        Path file = dataDir.resolve("prefixed.txt");
        Files.writeString(file, "http://example.com\nhello world");

        String[] captured = {null};
        EmbeddingIndex index = new EmbeddingIndex(
                text -> { captured[0] = text; return new float[]{1.0f}; },
                dataDir.toString(), "nomic-embed-text", "search_document:", "search_query:", 0f);

        index.indexFile(file);

        assertEquals("search_document: hello world", captured[0]);
        cleanup(file);
    }

    @Test
    void findSimilarAppliesQueryPrefix() {
        String[] captured = {null};
        EmbeddingIndex index = new EmbeddingIndex(
                text -> { captured[0] = text; return null; },
                dataDir.toString(), "nomic-embed-text", "search_document:", "search_query:", 0f);

        index.findSimilar("hello", 5);

        assertEquals("search_query: hello", captured[0]);
    }
```

`java.util.concurrent.atomic.AtomicInteger` is already imported in this file (used by the existing `indexAllSkipsAlreadyIndexedFiles` test) — no import changes needed.

- [ ] **Step 2: Run the tests to verify the new ones fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=EmbeddingIndexTest`
Expected: COMPILE ERROR — no 6-arg `EmbeddingIndex` constructor, no `deleteByModelNot` wiring, `ScoredResult` has no `chunkText`

- [ ] **Step 3: Rewrite `EmbeddingIndex`**

```java
package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores and queries dense vector embeddings for files in mcp-resources/.
 * Documents are split into chunks (see {@link Chunker}); each chunk gets its own row in the
 * MCP_EMBEDDING SQLite table. A unit-normalized vector cache is kept in memory for fast
 * dot-product scoring. Falls back gracefully when the EmbeddingClient (Ollama) is unavailable.
 */
@Component
public class EmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);

    private final EmbeddingClient embeddingClient;
    private final Path mcpResourcesDir;
    private final String model;
    private final String documentPrefix;
    private final String queryPrefix;
    private final float minScore;
    private final Map<CacheKey, CachedEmbedding> cache = new ConcurrentHashMap<>();

    @Autowired
    public EmbeddingIndex(
            EmbeddingClient embeddingClient,
            @Value("${data.dir:.}") String dataDir,
            @Value("${ollama.embedding.model:nomic-embed-text}") String model,
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

    /**
     * Convenience constructor for tests: default model, no task prefixes, no score threshold.
     * Public (not package-private) because {@code FileChangeWatcherTest} and
     * {@code DefaultDigitalMeStorageTest} construct EmbeddingIndex from other packages.
     */
    public EmbeddingIndex(EmbeddingClient embeddingClient, String dataDir) {
        this(embeddingClient, dataDir, "nomic-embed-text", "", "", 0f);
    }

    private record CacheKey(String filePath, int chunkIndex) {}

    private record CachedEmbedding(String sourceUrl, String chunkText, float[] vector) {}

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
            reconcileStaleFiles();
            McpEmbeddingDao.deleteByModelNot(model);
            loadCacheFromDatabase();
            Set<String> indexed = McpEmbeddingDao.findAllFilePaths();
            try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    if (!indexed.contains(file.toAbsolutePath().toString())) indexFile(file);
                });
            }
        } catch (Exception e) {
            log.warn("Error during startup embedding indexing", e);
        }
    }

    private void reconcileStaleFiles() throws Exception {
        Set<String> diskPaths = new HashSet<>();
        try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> diskPaths.add(f.toAbsolutePath().toString()));
        }
        for (String dbPath : McpEmbeddingDao.findAllFilePaths()) {
            if (!diskPaths.contains(dbPath)) {
                McpEmbeddingDao.deleteByFilePath(dbPath);
            }
        }
    }

    private void loadCacheFromDatabase() {
        cache.clear();
        for (McpEmbedding e : McpEmbeddingDao.findAll()) {
            cache.put(new CacheKey(e.filePath, e.chunkIndex),
                    new CachedEmbedding(e.sourceUrl, e.chunkText, fromBytes(e.embedding)));
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

            List<McpEmbedding> rows = new ArrayList<>();
            List<String> chunks = Chunker.chunk(body);
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String toEmbed = documentPrefix.isEmpty() ? chunkText : documentPrefix + " " + chunkText;
                float[] embedding = embeddingClient.embed(toEmbed);
                if (embedding == null) return; // Ollama unavailable — retry the whole file next pass
                rows.add(new McpEmbedding(filePath, i, sourceUrl, chunkText,
                        toBytes(normalize(embedding)), model, indexedAt));
            }
            for (McpEmbedding row : rows) {
                McpEmbeddingDao.upsert(row);
                cache.put(new CacheKey(row.filePath, row.chunkIndex),
                        new CachedEmbedding(row.sourceUrl, row.chunkText, fromBytes(row.embedding)));
            }
            log.debug("Indexed {} chunk(s) for {}", rows.size(), file.getFileName());
        } catch (Exception e) {
            log.warn("Error indexing embedding for {}", file, e);
        }
    }

    /**
     * Embeds the query and returns the top-K most similar files by cosine similarity,
     * deduplicated to each file's single best-scoring chunk.
     * Returns an empty list if Ollama is unavailable or no embeddings are stored.
     */
    public List<ScoredResult> findSimilar(String query, int topK) {
        String prefixedQuery = queryPrefix.isEmpty() ? query : queryPrefix + " " + query;
        float[] rawQueryEmbedding = embeddingClient.embed(prefixedQuery);
        if (rawQueryEmbedding == null) return List.of();
        float[] queryVector = normalize(rawQueryEmbedding);
        try {
            List<ScoredResult> scoredChunks = cache.entrySet().stream()
                    .map(e -> new ScoredResult(
                            e.getKey().filePath(),
                            e.getValue().sourceUrl(),
                            dot(queryVector, e.getValue().vector()),
                            e.getValue().chunkText()))
                    .filter(r -> r.score() >= minScore)
                    .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                    .toList();

            Map<String, ScoredResult> bestPerFile = new LinkedHashMap<>();
            for (ScoredResult r : scoredChunks) {
                bestPerFile.putIfAbsent(r.filePath(), r);
            }
            return bestPerFile.values().stream().limit(topK).toList();
        } catch (Exception e) {
            log.warn("Embedding search failed", e);
            return List.of();
        }
    }

    public record ScoredResult(String filePath, String sourceUrl, float score, String chunkText) {}

    private static float dot(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double sum = 0;
        for (int i = 0; i < len; i++) sum += a[i] * (double) b[i];
        return (float) sum;
    }

    private static float[] normalize(float[] v) {
        double magnitude = 0;
        for (float f : v) magnitude += f * (double) f;
        magnitude = Math.sqrt(magnitude);
        if (magnitude == 0) return v.clone();
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / magnitude);
        return out;
    }

    private static byte[] toBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * Float.BYTES);
        for (float f : floats) buf.putFloat(f);
        return buf.array();
    }

    private static float[] fromBytes(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) floats[i] = buf.getFloat();
        return floats;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=EmbeddingIndexTest`
Expected: PASS (all tests — 5 pre-existing + 7 new)

- [ ] **Step 5: Run the dependent MCP tool tests to check for regressions**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=McpSearchToolTest,McpFetchToolTest,McpResourceHandlerTest`
Expected: PASS (unchanged — these all construct `EmbeddingIndex` via the 2-arg convenience constructor with `text -> null`, forcing the keyword-scan fallback path, which this task didn't touch)

- [ ] **Step 6: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java src/test/java/com/breynisson/router/mcp/EmbeddingIndexTest.java
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: chunk-level embedding index with prefixes, cache, threshold, dedup, reconciliation"
```

---

### Task 4: `SemanticSearch` snippet-from-chunk + config + docs

**Files:**
- Modify: `src/main/java/com/breynisson/router/digitalme/SemanticSearch.java`
- Create: `src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `docs/architecture.md`
- Modify: `docs/mcp.md`

**Interfaces:**
- Consumes: `EmbeddingIndex.findSimilar(String, int) -> List<ScoredResult>` where `ScoredResult` has `.filePath()`, `.sourceUrl()`, `.score()`, `.chunkText()` (Task 3); `EmbeddingIndex(EmbeddingClient, String dataDir)` convenience constructor (Task 3).
- Produces: `SemanticSearch.search(String query) -> List<SearchResult>` (signature unchanged); `SemanticSearch.snippet(String raw) -> String` (behavior unchanged — still strips the first line); `SemanticSearch.chunkSnippet(String chunkText) -> String` (new — normalizes/truncates without stripping a header line). `McpServerConfig` already calls `SemanticSearch.snippet(raw)` for its keyword-fallback path (`McpServerConfig.java:152`) — unaffected, no changes needed there.

- [ ] **Step 1: Write the failing test**

```java
package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void searchUsesMatchingChunkTextAsSnippetNotWholeFile() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("doc.txt");
        String body = "Filler sentence about nothing in particular. ".repeat(100)
                + "The matching answer about llamas is here. ".repeat(50);
        Files.writeString(file, "http://example.com/doc\n" + body);

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(
                text -> text.contains("llamas") ? new float[]{1.0f, 0.0f} : new float[]{0.0f, 1.0f},
                dataDir.toString());
        embeddingIndex.indexFile(file);

        SemanticSearch semanticSearch = new SemanticSearch(embeddingIndex, text -> null, dataDir.toString());
        List<SearchResult> results = semanticSearch.search("llamas");

        assertEquals(1, results.size());
        assertTrue(results.get(0).snippet().contains("llamas"),
                "Snippet should come from the matching chunk, not an arbitrary slice of the file: "
                        + results.get(0).snippet());
    }

    @Test
    void snippetStripsSourceUrlHeaderLine() {
        String raw = "http://example.com\nActual content here.";
        assertEquals("Actual content here.", SemanticSearch.snippet(raw));
    }

    @Test
    void chunkSnippetDoesNotStripFirstLine() {
        String chunkText = "First sentence of the chunk. Second sentence.";
        assertEquals(chunkText, SemanticSearch.chunkSnippet(chunkText));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=SemanticSearchTest`
Expected: COMPILE ERROR — `SemanticSearch.chunkSnippet` doesn't exist yet

- [ ] **Step 3: Update `SemanticSearch`**

```java
package com.breynisson.router.digitalme;

import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import com.breynisson.router.mcp.SummarizeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
public class SemanticSearch {

    private static final int SNIPPET_CHARS = 2_000;
    private static final int FINAL_RESULT_LIMIT = 50;

    private final EmbeddingIndex embeddingIndex;
    private final SummarizeClient summarizeClient;
    private final Path mcpResourcesDir;

    public SemanticSearch(
            EmbeddingIndex embeddingIndex,
            SummarizeClient summarizeClient,
            @Value("${data.dir:.}") String dataDir) {
        this.embeddingIndex = embeddingIndex;
        this.summarizeClient = summarizeClient;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
    }

    /** Returns up to FINAL_RESULT_LIMIT semantically similar results; empty list if Ollama is unavailable. */
    public List<SearchResult> search(String query) {
        return embeddingIndex.findSimilar(query, FINAL_RESULT_LIMIT).stream()
                .filter(r -> !ExclusionRules.isExcluded(r.sourceUrl()))
                .map(r -> {
                    Path p = Path.of(r.filePath());
                    return new SearchResult(r.sourceUrl(), p.getFileName().toString(),
                            chunkSnippet(r.chunkText()), (double) r.score());
                })
                .toList();
    }

    /** Summarizes the given text; returns null if Ollama is unavailable. */
    public String summarize(String text) {
        return summarizeClient.summarize(text);
    }

    /** Extracts content after the first line (source URL), normalised and capped at SNIPPET_CHARS. */
    public static String snippet(String raw) {
        int nl = raw.indexOf('\n');
        String body = nl >= 0 ? raw.substring(nl + 1) : "";
        return normalizeAndTruncate(body);
    }

    /** Normalises and caps an already-extracted chunk of text (no header line to strip). */
    public static String chunkSnippet(String chunkText) {
        return normalizeAndTruncate(chunkText);
    }

    private static String normalizeAndTruncate(String body) {
        boolean truncated = body.length() > SNIPPET_CHARS;
        if (truncated) body = body.substring(0, SNIPPET_CHARS);
        String result = body.replace("\\n", " ").replace("\\t", " ").replace("\\r", " ")
                            .replaceAll("\\s+", " ").strip();
        return truncated ? result + " <truncated, use fetch tool>" : result;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=SemanticSearchTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Run the MCP tool tests to check for regressions**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=McpSearchToolTest`
Expected: PASS — these use `SemanticSearch.snippet(raw)` indirectly via the keyword-fallback path in `McpServerConfig`, which is unchanged

- [ ] **Step 6: Add the new properties to `application.properties`**

Append after the existing commented-out `ollama.*` lines:

```properties
# ollama.embedding.document-prefix=search_document:
# ollama.embedding.query-prefix=search_query:
# semantic-search.min-score=0.5
```

- [ ] **Step 7: Update `docs/architecture.md`**

In the `MCP_EMBEDDING` schema block, replace:

```
MCP_EMBEDDING (FILE_PATH PK, SOURCE_URL, EMBEDDING BLOB, INDEXED_AT)  -- vector embeddings for semantic search
```

with:

```
MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING BLOB, MODEL, INDEXED_AT, PK(FILE_PATH, CHUNK_INDEX))  -- chunked vector embeddings
```

In the `EmbeddingIndex` bullet list, replace the `indexFile(path)` and `findSimilar(query, topK)` bullets with:

```
- `indexFile(path)`: reads the file, splits the body into ~2000-char sentence-boundary-aware chunks via `Chunker`, embeds each chunk (prefixed with `ollama.embedding.document-prefix`), stores one row per chunk in `MCP_EMBEDDING` with a unit-normalized vector, and adds each to the in-memory cache
- `findSimilar(query, topK)`: embeds the (prefixed) query, scores every cached chunk vector via dot product, drops chunks below `semantic-search.min-score`, dedups to each file's best-scoring chunk, and returns the top-K `ScoredResult` records (`filePath`, `sourceUrl`, `score`, `chunkText`)
- `indexAll()` additionally reconciles the table on each run: deletes rows for files no longer on disk, and deletes rows whose `MODEL` doesn't match the currently configured `ollama.embedding.model` (both get re-embedded on the same pass)
```

Also replace the `McpEmbeddingDao` and `SemanticSearch` bullet lists (these still describe the pre-chunking single-row-per-file behavior) with:

```
### `McpEmbeddingDao`
- `upsert(McpEmbedding)` — INSERT OR REPLACE into `MCP_EMBEDDING`, keyed by `(FILE_PATH, CHUNK_INDEX)`
- `findAll()` — returns list of `McpEmbedding` (reads FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING columns; MODEL/INDEXED_AT come back null, not needed for search)
- `findAllFilePaths()` — returns `Set<String>` of already-indexed paths, `SELECT DISTINCT` since multiple chunk rows share a file path
- `deleteByFilePath(filePath)` — deletes all chunk rows for a file (used to reconcile deleted files)
- `deleteByModelNot(currentModel)` — deletes rows whose `MODEL` doesn't match the currently configured embedding model

### `SemanticSearch`
- Spring `@Component` combining `EmbeddingIndex` + `SummarizeClient`
- `search(query)`: calls `EmbeddingIndex.findSimilar(query, FINAL_RESULT_LIMIT=50)`, filters via `ExclusionRules`, returns list of `{source, name, snippet}` maps with the snippet built from the winning chunk's text
- `summarize(text)`: delegates to `SummarizeClient`; returns null when Ollama is unavailable
- `snippet(raw)` (static): strips first line (source URL), normalises whitespace, caps at 2000 chars; appends `<truncated, use fetch tool>` if truncated — used by the keyword-search fallback, which still reads whole files
- `chunkSnippet(chunkText)` (static): same normalisation/truncation as `snippet()` but without stripping a header line, since chunk text has no source-URL header — used by semantic search results
```

- [ ] **Step 8: Update `docs/mcp.md`**

In the "Search tool behaviour" section, update point 3 to:

```
3. Each result includes `source` (URL), `name` (filename), and `snippet` — for semantic results, built directly from the matching chunk's text (`SemanticSearch.chunkSnippet()`); for the keyword fallback, the first 2000 chars of file content after the source URL line (`SemanticSearch.snippet()`), whitespace-normalised; truncated snippets include `<truncated, use fetch tool>` hint
```

- [ ] **Step 9: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add src/main/java/com/breynisson/router/digitalme/SemanticSearch.java src/test/java/com/breynisson/router/digitalme/SemanticSearchTest.java src/main/resources/application.properties docs/architecture.md docs/mcp.md
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: build semantic search snippets from matching chunk text; update config and docs"
```

---

### Task 5: Full verification pass

**Files:** none (verification only)

**Interfaces:** none

- [ ] **Step 1: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: PASS (0 failures, 0 errors)

- [ ] **Step 2: Run checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: BUILD SUCCESS, no violations

- [ ] **Step 3: Run `/simplify` on the changed files**

Invoke the `/simplify` skill (per project `CLAUDE.md` workflow rule) over the files touched in Tasks 1–4: `Chunker.java`, `McpEmbedding.java`, `McpEmbeddingDao.java`, `EmbeddingIndex.java`, `SemanticSearch.java`, and their tests. Apply any simplification fixes it suggests, then re-run the full test suite (Step 1) to confirm nothing broke.

- [ ] **Step 4: Full package build (verifies frontend build + jar packaging still succeed)**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" package`
Expected: BUILD SUCCESS

- [ ] **Step 5: Manual smoke test**

With Ollama running locally (`ollama serve`, models `nomic-embed-text` pulled) and the app started from `digital-me-dev/` (`java -jar target/digital-me-0.1.jar`), drop a long (>2000 char) `.txt` file into `digital-me-dev/mcp-resources/<year-month>/`, wait for startup indexing to finish (check logs for "Indexed N chunk(s)"), then query `GET /semanticSearch?keywords=<a phrase from late in the document>` and confirm a result comes back with a snippet containing that phrase (proving chunking + retrieval works beyond the old 3000–4000 char cutoff).

- [ ] **Step 6: Final commit if `/simplify` made changes**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add -A
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "chore: simplify embeddings/semantic search changes"
```

(Skip this step if `/simplify` made no changes.)

---

## Out of Scope

Per the design spec: P8 (indexing coverage/observability metric), `sqlite-vec`/HNSW ANN indexing, hybrid RRF ranking, and unifying the Lucene/PDF corpus with the embedding corpus. These are candidates for a follow-up plan.
