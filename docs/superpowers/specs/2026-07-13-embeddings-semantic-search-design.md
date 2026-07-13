# Embeddings & Semantic Search Improvements — Design Spec
Date: 2026-07-13

## Problem

`docs/reviews/2026-07-13-embeddings-semantic-search-improvements.md` identifies eight
correctness/quality/scalability issues in the semantic search pipeline (`OllamaEmbeddingClient`,
`EmbeddingIndex`, `SemanticSearch`, `McpEmbeddingDao`). This spec covers a single batched pass
addressing P1–P7:

- **P1** — documents are truncated to 3000–4000 chars; only the first chunk of long articles is
  ever searchable
- **P2** — no `nomic-embed-text` task prefixes (`search_document:` / `search_query:`)
- **P3 (low effort)** — every query does a full-table scan + full cosine computation
- **P4** — no minimum score threshold; irrelevant matches are always returned
- **P5** — `topK` is requested as 50 but never actually enforced as a final limit
- **P6** — deleted files' embedding rows are never cleaned up
- **P7** — no `MODEL` column; a model change silently corrupts comparisons

**Out of scope** (deferred to a later pass): P8 (indexing coverage/observability metric),
`sqlite-vec`/HNSW approximate nearest-neighbor indexing, hybrid RRF ranking across Lucene +
embedding scores, and unifying the Lucene/PDF corpus with the embedding corpus.

## Solution Overview

Batch all four schema-affecting items (P1, P2, P6, P7) into one migration + one re-embed pass,
plus the cheap non-schema fixes (P3 low effort, P4, P5), since they touch the same code paths.
Chunk each document into ~2000-char, sentence-boundary-aware windows; embed each chunk with
nomic task prefixes; cache unit-normalized vectors in memory for fast dot-product scoring;
threshold and dedup-to-file before returning a final top-50.

---

## 1. Schema Migration (P1, P7)

New file `src/main/resources/digital-me-db-4.sql`. Drops and recreates `MCP_EMBEDDING`
(wipe-and-rebuild — old rows can't satisfy the new `NOT NULL` columns and aren't worth
preserving):

```sql
DROP TABLE IF EXISTS MCP_EMBEDDING;

CREATE TABLE MCP_EMBEDDING (
    FILE_PATH   TEXT NOT NULL,
    CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
    SOURCE_URL  TEXT NOT NULL,
    CHUNK_TEXT  TEXT NOT NULL,        -- exact text embedded (used for snippets)
    EMBEDDING   BLOB NOT NULL,        -- unit-normalised float32, packed big-endian
    MODEL       TEXT NOT NULL,
    INDEXED_AT  TEXT NOT NULL,
    PRIMARY KEY (FILE_PATH, CHUNK_INDEX)
);
```

After migration, `indexAll()` (existing startup daemon thread) finds no files "already indexed"
(the table is empty) and re-walks `mcp-resources/`, re-embedding every file from scratch under
the new chunked scheme. The app stays up during this; semantic search returns empty results
until the pass completes, same as any cold-start behavior today.

---

## 2. Chunking (P1)

New class `com.breynisson.router.mcp.Chunker` (or similar package-local utility), used by
`EmbeddingIndex.indexFile()` in place of the current single `MAX_EMBED_CHARS` truncation.

**Algorithm** (fixed-size window with sentence-boundary snapping):

1. Target chunk size: **2000 chars**.
2. From the current chunk start position `s`, look at the natural end point `s + 2000`.
3. Search backward from `s + 2000` up to 500 chars for the last occurrence of a
   sentence-ending punctuation mark (`.`, `!`, `?`) followed by whitespace or end-of-string.
4. If found at position `p`: the chunk is `[s, p)`. The next chunk starts at `p` — i.e. the
   sentence that would otherwise have been cut becomes the first sentence of the next chunk.
   This is the overlap mechanism; there is no separate fixed-length overlap constant.
5. If no sentence boundary is found within the 500-char lookback (e.g. a wall of text with no
   punctuation): hard-cut the chunk at `s + 2000` and start the next chunk at `s + 2000` with no
   overlap. This is an accepted, rare edge case.
6. The final chunk of a document may be shorter than 2000 chars; it's still embedded as its own
   chunk (no minimum size).

**Worked example:** sentence boundaries at chars 1847, 2210, 2650; target 2000. The window
`[0, 2000)` lands mid-sentence (inside the 1847–2210 span), so it snaps back: chunk 0 is
`[0, 1847)`. Chunk 1 starts at 1847 and repeats the process from there.

`EmbeddingIndex.indexFile()` now writes one `MCP_EMBEDDING` row per chunk, keyed by
`(filePath, chunkIndex)`, storing the chunk's exact text in `CHUNK_TEXT`.

---

## 3. Task Prefixes (P2)

`application.properties` gains two new properties (values have **no** trailing space — trailing
whitespace in `.properties` files is fragile, easily stripped by editors/tools on save):

```properties
ollama.embedding.document-prefix=search_document:
ollama.embedding.query-prefix=search_query:
```

Set either to an empty string to disable (for models that don't use nomic-style task prefixes).
When a prefix is non-empty, the code joins it to the text with a single space it inserts itself
(`prefix + " " + text`), rather than relying on a trailing space baked into the config value.

- `EmbeddingIndex.indexFile()` prepends `document-prefix` to each chunk's text before calling
  `embeddingClient.embed()`.
- `EmbeddingIndex.findSimilar()` prepends `query-prefix` to the search query before embedding it.
- `OllamaEmbeddingClient.embed()` is unchanged — it stays prefix-agnostic and just embeds
  whatever text it receives (normalisation/truncation logic untouched).

---

## 4. In-Memory Vector Cache (P3, low effort)

`EmbeddingIndex` holds an in-memory cache of `(filePath, chunkIndex) → unit-normalized float[]`
(e.g. a `ConcurrentHashMap`), populated once from `McpEmbeddingDao.findAll()` after the startup
`indexAll()`/reconciliation pass, then kept in sync incrementally:

- `indexFile()` adds/replaces its chunk entries in the cache after a successful DB write.
- Reconciliation deletions (section 6) remove the corresponding cache entries.

Vectors are normalized to unit length before being stored (both in the cache and in the
`EMBEDDING` blob), so `findSimilar()`'s scoring step is a plain dot product instead of full
cosine similarity (no sqrt, no magnitude passes at query time).

---

## 5. Threshold, Dedup, Final Limit (P4, P5)

All inside `EmbeddingIndex.findSimilar(query, topK=50)`:

1. Embed the (prefixed) query.
2. Score every cached chunk vector via dot product.
3. Drop chunks scoring below `semantic-search.min-score` (new property, default `0.5`).
4. Sort descending by score.
5. Dedup by `filePath`, keeping only the first (highest-scoring) occurrence per file — carrying
   that chunk's `CHUNK_TEXT` and `sourceUrl` forward. `ScoredResult` gains a `chunkText` field.
6. Take the top `topK` (50) distinct files.

`SemanticSearch.search()` continues to apply `ExclusionRules` filtering after `findSimilar()`
returns (unchanged ordering — exclusion isn't a relevance signal, so it stays a post-filter).
It builds the snippet directly from the winning chunk's `chunkText` (via the existing
`snippet()` normalisation) instead of re-reading and re-truncating the whole file from disk.
This also fixes the "snippet ≠ embedded text" issue noted in the review's secondary
observations, since the snippet now always contains the text that actually matched.

`semantic-search.min-score` is added to `application.properties`:

```properties
semantic-search.min-score=0.5
```

---

## 6. Stale Row Reconciliation (P6) & Model Tracking (P7)

Folded into the existing startup `indexAll()` pass (same daemon thread, no new scheduler),
run before the indexing walk:

1. Walk `mcp-resources/` (already done) to get the set of file paths that exist on disk.
2. Query distinct `FILE_PATH`s currently in `MCP_EMBEDDING` that are **not** in that set →
   delete all their rows via `McpEmbeddingDao.deleteByFilePath()` (already exists, currently
   unused).
3. Query rows where `MODEL` doesn't match the currently configured
   `ollama.embedding.model` → delete those too (new `McpEmbeddingDao.deleteByModelNot(model)`).
4. Because deleting a file's rows removes it from "already indexed," the normal indexing walk
   in the same pass picks it back up and re-embeds it under the current model.

`EmbeddingIndex` reads `@Value("${ollama.embedding.model:nomic-embed-text}")` directly (the
same property `OllamaEmbeddingClient` already uses) and stamps it into every row's `MODEL`
column. No change to the `EmbeddingClient` functional interface is needed.

---

## Testing

- **New `ChunkerTest`** — sentence-boundary snapping, hard-cut fallback (no punctuation within
  lookback), overlap via repeated sentence, single-chunk short documents.
- **`EmbeddingIndexTest` updates** — chunked indexing produces multiple rows per file;
  `findSimilar` dedup-by-file + threshold + cache behavior; reconciliation deletes rows for
  missing files and mismatched models.
- **`McpEmbeddingDaoTest` updates** — new schema columns, `deleteByFilePath`, new
  `deleteByModelNot`.
- **`SemanticSearchTest`** (or equivalent) — snippet is sourced from `CHUNK_TEXT`, not a
  re-read of the file.
- **Existing `McpSearchToolTest` / `McpResourceHandlerTest` / `McpFetchToolTest`** — verified to
  still pass; `EmbeddingClient` remains a plain `float[] embed(String)` lambda, so existing
  mocks (`text -> null`, `text -> new float[]{...}`) are unaffected.

---

## Out of Scope

- P8 — indexing coverage/observability metric (`/health/index` or similar)
- `sqlite-vec` / HNSW approximate nearest-neighbor indexing
- Hybrid Reciprocal Rank Fusion across Lucene keyword scores and embedding scores
- Unifying the Lucene/PDF full-text corpus with the embedding corpus (currently different sets
  of files)
