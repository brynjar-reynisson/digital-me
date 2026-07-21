# Re-assessment: Embeddings & Semantic Search — what's done, what's left

After re-scanning the codebase against the original review's eight problems:

## ✅ Already implemented

| # | Problem | How it was solved |
|---|---|---|
| **P1** | Only first ~3000 chars embedded | `Chunker` class splits documents at ~2000-char sentence boundaries; `MCP_EMBEDDING` now stores per-chunk rows keyed by `(FILE_PATH, CHUNK_INDEX)`; `ScoredResult` carries `chunkText` for accurate snippets. |
| **P2** | Missing nomic task prefixes | `documentPrefix` ("search_document:") and `queryPrefix` ("search_query:") are injected via `@Value` with sensible defaults, applied at embed time. |
| **P3** | Linear O(N) full-table scan | `ConcurrentHashMap<CacheKey, CachedEmbedding>` cache loaded at startup; vectors are **unit-normalized** at index time so search is a dot product (not full cosine); DB is only hit on startup, not per query. |
| **P4** | No score threshold | `minScore` (default 0.5) injected via `@Value`, filtered in `findSimilar()`. |
| **P5** | Filtering after limiting | `findSimilar()` now deduplicates by `sourceUrl` (best-scoring chunk wins) *before* the final `limit(topK)`; exclusions still happen in `SemanticSearch` but fewer slots are wasted due to de-duplication. |
| **P6** | Stale embeddings never cleaned | `reconcileStaleFiles()` in `indexAll()` deletes DB rows for files no longer on disk. `ResourceReceiver.deleteExistingFor()` also cleans up old rows/files when a source is re-submitted. |
| **P7** | Model version not tracked | `MODEL` column in `MCP_EMBEDDING`; `deleteByModelNot(currentModel)` runs at startup to purge incompatible vectors. |

## ❌ P8 — No observability / coverage endpoint (still open)

There is no way to know:
- How many files in `mcp-resources/` have been indexed vs. total
- How many chunks/vectors exist in total
- What model is currently active
- Whether the index is healthy or falling behind

`/health/ollama` only reports whether Ollama is reachable — nothing about the index itself.

## Other improvements found (not in the original review, already done)

- **Summary cache** (`SUMMARY_CACHE` table, `SummaryCacheDao`) — summarization results are cached per source URL, avoiding repeated Ollama calls.
- **AddContent queue** (`ADD_CONTENT_QUEUE` table, `AddContentQueueProcessor`) — `/addContent` now enqueues and processes asynchronously with retry (max 5 attempts, 2s poll interval).
- **Embedding worker pool** — a dedicated thread pool (`embedding-executor`, default size 1) processes embedding jobs from `/addContent` calls, preventing Ollama from being hammered during bursty browsing.
- **Re-submission safety** — `ResourceReceiver.deleteExistingFor()` deletes old files + embedding rows for the same source URL before writing new ones, so re-visited pages are always fresh.
- **Chunker tests** (`ChunkerTest.java`).

## Remaining minor issues (not worth a separate item)

- Whitespace/escape normalisation is still duplicated: `OllamaEmbeddingClient.normalise()` and `SemanticSearch.normalizeAndTruncate()` both contain the same `replace/replaceAll` logic. A shared utility would avoid drift.
- `FileChangeWatcher` handles `.txt/.md/.pdf` into Lucene but `mcp-resources/` files are never PDFs — the embedding corpus and keyword-search corpus are different. Unification would be nice but is a separate feature.
- No hybrid ranking (RRF) — semantic and keyword results remain in separate panes in the UI.

## Recommendation

**Implement P8**: add a `/health/index` (or extend `/health/ollama`) endpoint returning:

```json
{
  "model": "nomic-embed-text",
  "totalFilesOnDisk": 1234,
  "indexedFiles": 1200,
  "totalChunks": 4500,
  "coveragePercent": 97.2
}
```

This is a small, self-contained change — add methods to count rows in `MCP_EMBEDDING` and a REST handler in `IndexPage`.
