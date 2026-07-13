# Review: Improving Embeddings & Semantic Search

Digital Me's semantic search works, but it has several correctness, quality, and
scalability weaknesses. This document maps the current implementation, identifies
concrete problems, and proposes prioritized improvements.

---

## 1. Current implementation map

| Concern | Where | Notes |
|---|---|---|
| Embedding generation | `OllamaEmbeddingClient.embed()` | Ollama `/api/embeddings`, `nomic-embed-text`, caps text at **3000 chars** after whitespace normalisation, `num_ctx=2048` |
| Storage | `MCP_EMBEDDING` table (`digital-me-db-2.sql`) | `FILE_PATH` PK, `SOURCE_URL`, `EMBEDDING` BLOB (packed big-endian float32), `INDEXED_AT` |
| Indexing | `EmbeddingIndex.indexFile()` | caps body at **4000 chars**, one embedding per file, runs on a daemon thread at startup |
| Search | `EmbeddingIndex.findSimilar(query, topK)` | loads **all** embeddings into memory, computes cosine in Java, sorts, returns top-K |
| Query path | `SemanticSearch.search()` → `findSimilar(query, 50)` | filters via `ExclusionRules`, builds snippets |
| MCP tool | `McpServerConfig.buildSearchHandler()` | semantic first, falls back to keyword scan when empty |
| REST | `IndexPage./semanticSearch` | wraps results in a `LinkedHashSet` (dedup by equality) |

The pipeline is coherent, but the details below limit result quality and will not
scale past a few thousand documents.

---

## 2. Problems, ranked by impact

### P1 — Only the first 3000–4000 chars of each document are ever embedded
`EmbeddingIndex.indexFile()` truncates the body to `MAX_EMBED_CHARS = 4000`, and
`OllamaEmbeddingClient` truncates again to `MAX_CHARS = 3000`. **A long article's
entire second half is invisible to semantic search.** For a "personal search
engine" indexing full web pages and PDFs, this is the single biggest quality loss.

- The two truncation limits (4000 then 3000) are also redundant and inconsistent —
  the 4000 cap does nothing because the client re-caps at 3000.

**Fix: chunk documents.** Split each file into overlapping windows
(e.g. ~1500–2000 chars, ~200 char overlap on sentence/paragraph boundaries),
embed each chunk, and store one row per chunk. Search ranks chunks, then
deduplicates to the parent file (keep max chunk score). This is the standard RAG
approach and directly unlocks recall on long documents.

### P2 — Query/document embedding asymmetry (nomic task prefixes)
`nomic-embed-text` is trained with **task instruction prefixes**:
`search_document:` for indexed text and `search_query:` for the query. The current
code sends raw text for both. Without the prefixes, retrieval quality is measurably
worse and the vectors are not in the space the model was tuned for.

**Fix:** prefix stored content with `search_document: ` and queries with
`search_query: ` before calling Ollama. This is a small change with a real recall
improvement. (Make it configurable so the code still works with models that don't
use prefixes.)

### P3 — Linear O(N) full-table scan on every query
`findSimilar()` does `McpEmbeddingDao.findAll()` — it loads **every** embedding
BLOB from SQLite into memory and computes cosine similarity in a Java stream on
each search. With 768-dim float vectors that's ~3 KB/doc; at 10k docs it reads
~30 MB and does 10k cosine computations per keystroke-driven search. It will not
scale and adds latency.

**Fixes (pick based on appetite):**
- **Low effort:** cache the parsed `float[]` vectors in memory (a
  `Map<filePath, float[]>`) refreshed on index changes, so queries skip the DB
  read + BLOB unpack. Pre-normalise vectors to unit length at index time so the
  query is a dot product, not a full cosine.
- **Higher effort:** adopt `sqlite-vec` (a vector extension for SQLite already in
  the stack) or an in-process ANN index (HNSW) for true sub-linear search.

### P4 — No score threshold: irrelevant results always returned
`findSimilar` returns the top-K by score regardless of how low the score is. If
nothing is relevant, the user still gets 50/10 weakly-matching documents. Cosine
scores near 0 are noise.

**Fix:** apply a minimum similarity threshold (tune empirically, e.g. 0.5–0.6 for
nomic) and drop results below it. Optionally expose the score in the UI so results
are explainable.

### P5 — `topK` mismatch between layers
`SemanticSearch.search()` requests `findSimilar(query, 50)` but the docs and
frontend describe top-10 (5 per page). The `50` was likely bumped to compensate
for `ExclusionRules` filtering happening *after* the limit — but filtering after
limiting means excluded results still consume slots and can starve good ones.

**Fix:** filter (exclusions + threshold) *before* applying the final limit, then
cap at the intended K. Make K a named constant, not a magic `50`.

### P6 — Stale / deleted embeddings never cleaned up
`indexAll()` only *adds* embeddings for files not yet indexed. There is no path
that removes `MCP_EMBEDDING` rows when a file is deleted or re-indexes when a file
changes (the PK is `FILE_PATH`, and `ResourceReceiver` writes unique timestamped
names, so content changes create new files but old rows linger). `deleteByFilePath`
exists but is never called.

**Fix:** on startup (and/or via a file watcher) reconcile the table against
`mcp-resources/` — delete rows whose files no longer exist. If chunking (P1) lands,
key rows by `(file_path, chunk_index)` and delete-then-reinsert on change.

### P7 — Model dimension / version not tracked
Embeddings from different models (or model versions) are not comparable, but the
schema stores no model identifier. If `ollama.embedding.model` changes, old vectors
silently corrupt search results.

**Fix:** add `MODEL` (and optionally `DIM`) columns; at query time only compare
vectors produced by the current model, and trigger a re-index when the model
changes.

### P8 — Silent failure modes reduce coverage invisibly
`embed()` returns `null` on any error and `indexFile()` simply `return`s. A
transient Ollama hiccup during startup indexing permanently skips that file (it's
now "seen" only if a row was written — actually it *isn't* written, so it retries
next startup, which is fine — but a persistent oversized-input error will loop
forever with no surfaced metric).

**Fix:** add a lightweight index-status/coverage metric (indexed vs. total files)
surfaced via the existing `/health/ollama` or a new `/health/index` endpoint, and
add retry/backoff for transient Ollama errors.

---

## 3. Secondary observations

- **Whitespace/escape normalisation** (`\\n`, `\\t`, `\\r`) is duplicated in
  `OllamaEmbeddingClient.normalise()` and `SemanticSearch.snippet()`. Extract to a
  shared util to keep them in sync.
- **`LinkedHashSet` dedup** in `IndexPage.semanticSearch` relies on
  `SearchResult.equals()`. Confirm `SearchResult` (a record?) has value equality
  that includes `score`; if score differs per near-duplicate, dedup won't fire.
- **Snippet ≠ embedded text.** The snippet shown to the user (first 2000 chars) may
  not contain the text that actually matched (which could be anywhere in the doc).
  Chunking (P1) fixes this naturally: show the matching chunk as the snippet.
- **PDFs / `.md` are full-text indexed (Lucene) but never embedded.** `FileChangeWatcher`
  handles `.txt/.md/.pdf` into `TEXT_ENTRY`/Lucene, but `EmbeddingIndex` only walks
  `mcp-resources/`. Semantic search therefore covers a *different* corpus than
  keyword search. Consider unifying the corpora so both search modes see the same
  documents.
- **No hybrid ranking.** Semantic and keyword results are shown in separate panes.
  A fused ranking (e.g. Reciprocal Rank Fusion of Lucene + embedding scores) would
  give better single-list results.

---

## 4. Recommended sequencing

1. **P2 (task prefixes)** — tiny change, immediate quality win. *(requires re-index)*
2. **P4 + P5 (threshold + filter-before-limit)** — cheap, removes junk results.
3. **P3 low-effort (in-memory vector cache + unit-normalise)** — latency + scale.
4. **P1 (chunking)** — biggest recall win; schema change to `(file_path, chunk_index)`.
5. **P6 + P7 (reconcile stale rows + model column)** — correctness over time.
6. **P8 (coverage metric)** — observability.
7. Optional: **sqlite-vec / HNSW** and **hybrid RRF ranking** for the next scale tier.

Items P1, P2, P6, P7 all imply a re-index, so batch them into one schema migration
(`digital-me-db-3.sql`) and one re-embedding pass to avoid multiple rebuilds.

---

## 5. Suggested schema (post-chunking, post-model-tracking)

```sql
CREATE TABLE IF NOT EXISTS MCP_EMBEDDING (
    FILE_PATH   TEXT NOT NULL,
    CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
    SOURCE_URL  TEXT NOT NULL,
    CHUNK_TEXT  TEXT NOT NULL,        -- the exact text embedded (for snippet)
    EMBEDDING   BLOB NOT NULL,        -- unit-normalised float32
    MODEL       TEXT NOT NULL,
    INDEXED_AT  TEXT NOT NULL,
    PRIMARY KEY (FILE_PATH, CHUNK_INDEX)
);
```

This keeps the existing packed-float BLOB format (backwards-compatible unpacking)
while enabling chunk-level retrieval, model-scoped comparison, and accurate
snippets.
