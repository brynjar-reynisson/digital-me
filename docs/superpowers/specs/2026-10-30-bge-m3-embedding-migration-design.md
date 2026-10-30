# Gradual migration of semantic search from `nomic-embed-text` to `bge-m3`

## Problem

Semantic search is weak on Icelandic. `nomic-embed-text` is an English-trained model: Icelandic words are split into unrelated sub-word pieces, so similarity mostly reflects shared word fragments rather than meaning. Live Icelandic queries (e.g. `skilnaður gömul kærasta`) returned only loose lexical overlaps with every score between 0.59 and 0.65.

### Evidence (experiment, 2026-10-30)

Self-retrieval test, 150 documents per language drawn from the real index: the first 1,500 characters of an article are embedded as the document; a sentence from later in the same article is the query; the metric is where the article ranks among the 150.

| Set | Model | R@1 | R@5 | R@10 | MRR | top score − median |
|---|---|---|---|---|---|---|
| Icelandic | nomic-embed-text | 0.19 | 0.33 | 0.43 | 0.277 | 0.07 |
| Icelandic | **bge-m3** | **0.39** | **0.52** | **0.61** | **0.466** | **0.15** |
| English | nomic-embed-text | 0.33 | 0.55 | 0.65 | 0.437 | 0.15 |
| English | bge-m3 | 0.37 | 0.57 | 0.64 | 0.468 | 0.15 |

Icelandic recall at rank 1 doubles; English is a tie (no regression). Caveats: news prose only (not diary text or OCR screenshots), 150-document haystack (the real one is ~126k chunks).

### Cost (measured)

- Corpus: 126,423 chunks across 21,525 files (`/health/index`).
- bge-m3 on CPU: ~2.8 s/chunk (about 4 days). With the Intel Iris Xe iGPU enabled (`OLLAMA_IGPU_ENABLE=1`, now set as a permanent user env var): **~0.8 s/chunk, roughly 27 hours** for a full re-embed.
- GPU vs CPU vectors are interchangeable: cosine similarity of the same text computed both ways is ≥ 0.9997 (bge-m3) / ≥ 0.99996 (nomic); top-1 matches preserved 24/24. Mixing GPU- and CPU-computed vectors in one index is safe.

## Decision

Migrate **all** content to `bge-m3` — not just Icelandic — so the end state is one embedding model, not a permanent double system. The migration is gradual and crash-safe: a full re-embed takes about a day of background GPU time, Windows may force a reboot at any moment, and search must keep working throughout.

## Scope

In scope: new vectors table, `MIGRATED_AT` tracking column on the old table, background backfill worker, dual-write for new content, two-table search with rank fusion, health/progress reporting, final cleanup.

Out of scope (separate work): hybrid keyword+semantic search blending, the `/digital-me` skill change to call both endpoints, re-chunking for bge-m3's larger context window (chunks are migrated 1:1 as-is), frontend changes.

## Design

### Overview

```
old table  MCP_EMBEDDING     vector(768)   nomic   + new column MIGRATED_AT (NULL = not yet migrated)
new table  MCP_EMBEDDING_V2  vector(1024)  bge-m3
```

Every file lives in exactly one searchable place at any moment: unmigrated files in the old table (`MIGRATED_AT IS NULL`), migrated files in the new table. Search queries both and fuses the rankings; the old-table side shrinks to nothing as the backfill progresses, at which point search is effectively v2-only with no flag flip. The old table stays in place (rows intact, just marked) as a rollback path until a deliberate cleanup step.

### Schema: `digital-me-db-3.sql`

```sql
ALTER TABLE MCP_EMBEDDING ADD COLUMN MIGRATED_AT TEXT;   -- ISO-8601 instant, NULL until the file is in V2
CREATE INDEX mcp_embedding_unmigrated_idx ON MCP_EMBEDDING (FILE_PATH) WHERE MIGRATED_AT IS NULL;

CREATE TABLE MCP_EMBEDDING_V2 (
    FILE_PATH   TEXT NOT NULL,
    CHUNK_INDEX INTEGER NOT NULL DEFAULT 0,
    SOURCE_URL  TEXT NOT NULL,
    CHUNK_TEXT  TEXT NOT NULL,
    EMBEDDING   extensions.VECTOR(1024) NOT NULL,
    MODEL       TEXT NOT NULL,
    INDEXED_AT  TEXT NOT NULL,
    PRIMARY KEY (FILE_PATH, CHUNK_INDEX)
);
CREATE INDEX mcp_embedding_v2_hnsw_idx ON MCP_EMBEDDING_V2
    USING hnsw (EMBEDDING extensions.vector_cosine_ops);
CREATE INDEX mcp_embedding_v2_source_idx ON MCP_EMBEDDING_V2 (SOURCE_URL);
```

Adding a nullable column without a default is a metadata-only change in Postgres (instant on 126k rows). The partial index makes "what is left to migrate?" cheap at any progress level, including zero. `database.version` becomes `3`.

### The migration unit and crash safety

The unit of migration is **one file (all its chunks)**, committed atomically:

1. Pick the next file: `SELECT DISTINCT FILE_PATH ... WHERE MIGRATED_AT IS NULL ORDER BY <priority tier>, ... LIMIT 1` (see Ordering).
2. Read that file's chunks from the **old table** (`CHUNK_INDEX`, `SOURCE_URL`, `CHUNK_TEXT`). The backfill embeds the *stored chunk text* — it does not re-read the file or re-chunk — so chunking is identical to what nomic saw, chunk indexes map 1:1, and files that were since deleted from disk can't cause I/O errors.
3. Embed each chunk with bge-m3 (the slow part, outside any DB transaction).
4. In **one transaction**: `INSERT ... ON CONFLICT DO UPDATE` all V2 rows for the file, then `UPDATE MCP_EMBEDDING SET MIGRATED_AT = now WHERE FILE_PATH = ? AND MIGRATED_AT IS NULL`. If the update's row count is not the number of chunks read in step 2 (the file was replaced or deleted concurrently, see below), **roll back** and move on.

Consequences:
- A crash, forced reboot or JVM kill at any point leaves a file either fully migrated (V2 rows + marked) or untouched (nothing written, still `NULL`). There is no half-migrated state. On restart the worker simply runs step 1 again and resumes where it left off — no separate checkpoint file is needed; `MIGRATED_AT` *is* the checkpoint.
- At most one file's embedding work (a few chunks, seconds) is lost to an interruption.
- The operation is idempotent: re-running over an already-migrated file is a no-op (it's filtered out by `MIGRATED_AT IS NULL`).

**Concurrent replacement/deletion.** `ResourceReceiver.deleteExistingFor()` deletes old-table rows by `SOURCE_URL` when content is resubmitted, and the stale-file reconcile deletes by `FILE_PATH`. If either lands between steps 2 and 4, the step-4 `UPDATE` matches fewer rows than expected, the transaction rolls back, and the V2 rows are never written. Both delete paths are extended to also delete from `MCP_EMBEDDING_V2`.

### Ordering

The backfill works in priority tiers so the content you most want to search benefits first:

1. Local files (`SOURCE_URL` not starting with `http`) — diary, Obsidian vault, notes, Claude sessions.
2. Icelandic web pages (`SOURCE_URL` host ending `.is`).
3. Everything else.

Within a tier, newest `INDEXED_AT` first. Because search fuses both tables throughout, partial progress is immediately useful rather than all-or-nothing.

### Backfill worker

- New `EmbeddingMigrator` component; one daemon thread started on `ApplicationReadyEvent` (not when the SQLite migration flag is set, same exemption as `EmbeddingIndex`).
- Loops: migrate the next file; sleep briefly when the queue is empty or Ollama is unavailable (back off 30 s, log once per outage, resume automatically). Never exits on error — a failure on one file is logged and that file is skipped for this pass.
- Single worker: Ollama serialises requests anyway, and a single worker keeps live indexing (`embedding.executor.pool-size`) responsive.
- Gated by `embedding.v2.enabled`; when false the worker, dual-write and fusion are all off and the app behaves exactly as today.

### Dual-write for new content

> Superseded - see Amendment 2026-10-30 at the end of this document.

While `embedding.v2.enabled=true`, `EmbeddingIndex.indexFile()` embeds each chunk with **both** models. If both succeed the old rows are written with `MIGRATED_AT` set and the V2 rows in the same transaction. If the bge-m3 embedding fails (Ollama/model problem) the file is written to the old table only with `MIGRATED_AT` left `NULL`, and the backfill worker catches it later — so the backfill is the universal catch-up mechanism and dual-write failures can never leave a gap.

Old-table writes continue during the soak period so rolling back remains valid.

### Search: two tables, rank fusion

> Superseded - see Amendment 2026-10-30 at the end of this document.

`EmbeddingIndex.findSimilar()`:
1. Embed the query with each model (nomic with `search_query:`, bge-m3 with no prefix). A model that returns null contributes no results (the other side still serves).
2. Old table query: as today, plus `WHERE MIGRATED_AT IS NULL` (only when v2 is enabled — otherwise no filter, so disabling v2 restores full pre-migration behaviour including already-migrated files). Cosine query against V2: the same `DISTINCT ON (source_url)` SQL against `MCP_EMBEDDING_V2`.
3. Each side returns its own top-K with its own min-score. Because the two sets of files are disjoint and the models' score scales differ (nomic top hits sit near 0.76, bge-m3 near 0.5), raw scores are **not** compared. Results are merged by **reciprocal rank fusion** on `source_url` (`1/(60 + rank)`), then trimmed to `topK`. The cosine score each result carries for display is its own side's score.

As the backfill proceeds, the old-table side empties and fusion degrades to "V2 results only". Once nothing is unmigrated, the old query returns no rows immediately (served from the partial index).

`semantic-search.min-score` (0.5) was calibrated for nomic. bge-m3's score distribution is lower (mean top score ~0.52 vs ~0.79 on Icelandic in the experiment), so a separate `embedding.v2.min-score` is added; its default will be chosen from a calibration pass during implementation before the flag is turned on. Leaving it at 0.5 would silently drop many valid bge-m3 hits.

### Embedding client wiring

`EmbeddingClient` stays a functional interface. `AppConfig` registers two named beans (`legacyEmbeddingClient` for `ollama.embedding.model`, `embeddingClientV2` for `embedding.v2.model`, default `bge-m3`), both `OllamaEmbeddingClient` instances differing only in model. `EmbeddingIndex` receives both and the per-model prefixes (`ollama.embedding.*-prefix` for nomic; `embedding.v2.document-prefix` / `query-prefix` default empty for bge-m3, which uses no task prefixes). Existing normalisation to unit length, the 3,000-character cap and `num_ctx=2048` apply to both.

`McpEmbeddingDao` methods take the target table so the same code serves both (a small refactor of the current static methods; `findSimilar` and `upsert` keep identical SQL apart from the table name).

`deleteByModelNot()` stays as is, applied per table against that table's configured model — the old table's model is unchanged, so the destructive wipe-on-model-change hazard of simply repointing `ollama.embedding.model` is avoided.

### Progress reporting

`/health/index` gains a `migration` object (present only when v2 is enabled): `{ migratedFiles, remainingFiles, migratedChunks, remainingChunks, percentMigrated }`, from two `COUNT` queries using `MIGRATED_AT`. This makes it easy to watch the backfill and to decide when to run cleanup.

### Cutover and cleanup

There is no manual cutover: search shifts automatically as `MIGRATED_AT` fills. After remaining = 0 and a soak period (suggest one week of normal use to confirm Icelandic search is good), a final, separate change:
- `digital-me-db-4.sql`: drop `MCP_EMBEDDING` (old table, its HNSW index and the `MIGRATED_AT` machinery), optionally rename `MCP_EMBEDDING_V2` → `MCP_EMBEDDING`;
- remove dual-write, fusion, the backfill worker, the `embedding.v2.enabled` flag and the legacy embedding client bean; set `ollama.embedding.model` default to `bge-m3` and the schema-comment dimension to 1024.

This is what ends the double system.

### Rollback

Until cleanup, setting `embedding.v2.enabled=false` returns the app to exact pre-migration behaviour (old table, nomic, no filter on `MIGRATED_AT`). No data is lost either way; a re-enable resumes the backfill from `MIGRATED_AT`.

## Testing

Following `docs/testing.md` (isolated Postgres schema per class, `EmbeddingClient` lambdas as mocks):

- `McpEmbeddingDao`: V2 upsert/find; delete-by-source and delete-by-file hit both tables; `MIGRATED_AT` filter and partial-index query.
- Migrator atomicity: an embed failure mid-file leaves zero V2 rows and `MIGRATED_AT` still NULL; a successful file has all V2 rows and all old rows marked; restarting the migrator picks up exactly the unmigrated files.
- Crash-resume: run the migrator over N files, stop partway, construct a fresh migrator, and assert only the remainder is processed and the end state is complete, with no duplicates.
- Concurrent replacement: delete the source's old rows between read and commit; assert rollback and no orphaned V2 rows.
- Ordering: local files before `.is` pages before the rest.
- Dual-write: both-models-succeed marks migrated; bge failure leaves `MIGRATED_AT` NULL and the backfill later picks it up.
- Fusion: disjoint result sets merged by rank; one side null/empty still returns the other; V2 disabled restores unfiltered old-table search.
- Health endpoint migration object.
- Manual verification against live Ollama on the GPU: backfill a small slice, then run the Icelandic queries from the experiment (`skilnaður gömul kærasta`, etc.) and compare before/after.

## Docs to update when this ships

`CLAUDE.md` (project structure), `docs/architecture.md` (schema section, `EmbeddingIndex`, `McpEmbeddingDao`, `/health/index`, new `EmbeddingMigrator`), `docs/mcp.md` (Ollama setup: `ollama pull bge-m3`, `OLLAMA_IGPU_ENABLE=1`), `docs/testing.md`.

## Open questions

1. **`embedding.v2.min-score` default** — to be calibrated against real queries before enabling (see Search).
2. **Chunk size** — bge-m3 supports 8,192 tokens, so larger chunks could improve recall for long documents; deferred to keep the migration 1:1 and low-risk.
3. **Soak duration before cleanup** — one week suggested.

## Amendment 2026-10-30 (after the live run)

Live testing showed that dual-write (every new chunk embedded by both nomic and bge-m3) and two-model fused search forced Ollama to swap models in and out of memory on a small iGPU, making searches take 10-13 s. The design was simplified to one model at a time:

- **New embeddings:** when `embedding.v2.enabled=true`, `indexFile` embeds with bge-m3 only and writes only to `MCP_EMBEDDING_V2`. The legacy table is untouched and the nomic client is not called. If any chunk embed fails, nothing is written and the file is retried on the next pass.
- **Search:** no more fusion. While the migration is incomplete, search queries only the legacy table with nomic (no `MIGRATED_AT` filter, so migrated files remain searchable through their nomic rows). When the migration is complete (`remainingChunks == 0`, cached: true permanently, false for 30 s) search queries only `MCP_EMBEDDING_V2` with bge-m3, using the v2 query prefix and min-score. The flip is automatic.
- **Accepted consequence:** content added after v2 is enabled is not semantically searchable until the migration completes; keyword (Lucene) search still finds it.
- **indexAll / reconcile:** a file counts as indexed if present in either table, and stale-file reconciliation uses the union of both tables' paths, so V2-only files are not re-embedded at startup and their rows are removed when the file is deleted.
- Removed: reciprocal rank fusion and `McpEmbeddingDao.upsertDual`.
