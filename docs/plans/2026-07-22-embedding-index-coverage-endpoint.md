# Plan: Embedding Index Coverage Endpoint (P8)

## Goal

Add a `/health/index` REST endpoint that reports embedding coverage metrics so the operator (and any monitoring tooling) can tell at a glance whether the embedding index is healthy, falling behind, or fully caught up.

## Background

The existing `/health/ollama` endpoint only reports whether Ollama is reachable. There is no visibility into the index itself — how many files are embedded, how many chunks exist, what model is active, or whether files in `mcp-resources/` are still waiting for their first embedding pass.

`EmbeddingIndex` already has all the data needed to compute these metrics; it just isn't exposed.

---

## Changes required

### 1. `McpEmbeddingDao` — add two query helpers

**File:** `src/main/java/com/breynisson/router/jdbc/McpEmbeddingDao.java`

Add two new static methods:

```java
/**
 * Returns the total number of distinct files that have at least one
 * embedding row in the table.
 */
public static int countIndexedFiles() {
    Integer count = DatabaseAdapter.selectSingle(
        "SELECT COUNT(DISTINCT FILE_PATH) FROM " + TABLE,
        DatabaseAdapter.RESULT_SET_INTEGER_TRANSFORM);
    return count != null ? count : 0;
}

/**
 * Returns the total number of embedding rows (chunks) in the table.
 */
public static int countTotalChunks() {
    Integer count = DatabaseAdapter.selectSingle(
        "SELECT COUNT(*) FROM " + TABLE,
        DatabaseAdapter.RESULT_SET_INTEGER_TRANSFORM);
    return count != null ? count : 0;
}
```

**Pre-check:** verify `DatabaseAdapter` has `RESULT_SET_INTEGER_TRANSFORM` and a `selectSingle` overload (or a `selectList` + `isEmpty` fallback). If not, the path of least resistance is to use the existing `selectList` pattern with a single-row query and extract `get(0)`.

### 2. `IndexPage` — add `/health/index` endpoint

**File:** `src/main/java/com/breynisson/router/ui/IndexPage.java`

Inject `EmbeddingIndex` (or pass the necessary data; `EmbeddingIndex` already has `mcpResourcesDir` and `embeddingClient`):

Add to the constructor:
```java
private final EmbeddingIndex embeddingIndex;
```

The `IndexPage` constructor already takes `EmbeddingClient`, `SemanticSearch`, etc. — add `EmbeddingIndex` to the parameter list (Spring will autowire it).

Add a new `@GetMapping`:

```java
@GetMapping("/health/index")
public Map<String, Object> indexHealth() {
    int indexedFiles = McpEmbeddingDao.countIndexedFiles();
    int totalChunks = McpEmbeddingDao.countTotalChunks();
    int totalFilesOnDisk = embeddingIndex.countFilesOnDisk();
    double coveragePercent = totalFilesOnDisk > 0
        ? Math.round(indexedFiles * 10000.0 / totalFilesOnDisk) / 100.0
        : 100.0;

    return Map.of(
        "indexedFiles", indexedFiles,
        "totalChunks", totalChunks,
        "totalFilesOnDisk", totalFilesOnDisk,
        "coveragePercent", coveragePercent
    );
}
```

### 3. `EmbeddingIndex` — expose `countFilesOnDisk()`

**File:** `src/main/java/com/breynisson/router/mcp/EmbeddingIndex.java`

Add a package-private or public method:

```java
/**
 * Counts regular files currently on disk under mcp-resources/.
 * Returns 0 if the directory does not exist or is empty.
 */
int countFilesOnDisk() {
    try {
        if (!Files.isDirectory(mcpResourcesDir)) return 0;
        try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    } catch (IOException e) {
        log.warn("Could not count files in {}", mcpResourcesDir, e);
        return 0;
    }
}
```

This reuses the same walk logic already present in `listFilePaths()` but counts rather than collecting into a set.

---

## Expected response shape

```json
{
  "indexedFiles": 1200,
  "totalChunks": 4500,
  "totalFilesOnDisk": 1234,
  "coveragePercent": 97.2
}
```

- `indexedFiles` — distinct `FILE_PATH` values in `MCP_EMBEDDING`
- `totalChunks` — total row count in `MCP_EMBEDDING`
- `totalFilesOnDisk` — regular files under `mcp-resources/` recursively
- `coveragePercent` — `indexedFiles / totalFilesOnDisk * 100`, rounded to one decimal; 100% when no files exist to avoid division by zero

---

## What NOT to do

- Do **not** expose the model name in this endpoint — the model is a deployment-level constant, and including it would lock the response shape to a specific dimension. The `/health/ollama` endpoint already confirms Ollama is up; the model configured in `application.properties` is static.
- Do **not** add a scheduled or background thread — the endpoint should be a lightweight instantaneous read; counting files in a medium directory (~thousands of files) takes a few milliseconds at most.
- Do **not** add authentication — this is a local single-user tool bound to `127.0.0.1`.

---

## Files touched

| File | Change |
|---|---|
| `McpEmbeddingDao.java` | Add `countIndexedFiles()`, `countTotalChunks()` |
| `EmbeddingIndex.java` | Add `countFilesOnDisk()` |
| `IndexPage.java` | Add constructor param `EmbeddingIndex` + `@GetMapping("/health/index")` |

No schema changes. No new dependencies. No config changes.

---

## Verification

1. Start the app with Ollama running and some files in `mcp-resources/`.
2. `GET http://localhost:8080/health/index` — expect `coveragePercent` somewhere between 0 and 100.
3. Add a new file to `mcp-resources/` (via the Chrome extension or MCP client), wait a few seconds for the background embedder, then hit the endpoint again — `indexedFiles` should increase.
4. Delete a file from `mcp-resources/`, restart the app — `indexedFiles` should drop after reconciliation.
5. Unit test: mock `EmbeddingIndex.countFilesOnDisk()` and verify the arithmetic in the health endpoint.
