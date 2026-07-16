# Summarize top 5 semantic results in parallel

## Problem

After a semantic search, only the #1 result gets an on-demand summary (`App.tsx` fetches `/summarize` for `results[0].snippet` once results arrive, showing "Summarizing…" then the text). Results #2–5 (the rest of the first semantic page, `SEMANTIC_PAGE_SIZE = 5`) get no summary at all. The user wants all 5 summarized, fetched concurrently so faster summaries appear without waiting on the slowest.

## Scope

Frontend only (`frontend/src/`). No backend changes — `POST /summarize` is already stateless and reentrant per call, so firing multiple concurrent requests against it needs no server-side support.

## Design

### State (`App.tsx`)

Replace the single `topSummary: string | null | undefined` state with a map keyed by result source:

```ts
const [summaries, setSummaries] = useState<Record<string, string | null>>({})
```

- Key absent → not requested (item wasn't in the top 5, or had no snippet)
- `null` → requested, still loading
- `string` (including `''`) → resolved; `''` means the request failed or returned no summary

After semantic search results arrive, reset `summaries` to `{}` (mirroring the current `setTopSummary(undefined)` reset at the start of `doSearch`), then take `results.slice(0, 5)` and, for each item with a `snippet`:
1. Set `summaries[item.source] = null` (loading)
2. Fire `POST /summarize` with `{ text: item.snippet }`
3. On resolution, update only that item's entry: `setSummaries(s => ({ ...s, [item.source]: data.summary || '' }))`
4. On failure, same but with `''`

Each of the 5 fetches is independent — no `Promise.all` gating across them — so the UI fills in per-item as each completes, matching the "in parallel" requirement.

### Prop threading (`ResultSection.tsx`, `ResultItem.tsx`)

Replace the `topSummary` / `isTop` props with a single `summaries: Record<string, string | null>` map, passed down unchanged from `App.tsx` → `ResultSection` → `ResultItem`. `ResultSection` no longer computes `isTop` from index/page position.

`ResultItem` looks up its own summary via `summaries[item.source]`:
- Key absent → render nothing (same as today's non-top items)
- `null` → render `"Summarizing…"` (existing `result-summary--loading` style)
- Non-empty string → render the summary (existing `result-summary` style)
- `''` → render nothing (failed/empty, same as today)

This removes the index-based `isTop` computation in `ResultSection` entirely — which items get a summary is now fully determined by whether they're a key in the map, decided once in `App.tsx`.

### Interaction with pagination

`SEMANTIC_PAGE_SIZE` is already 5, so the top 5 results are exactly page 1 of the semantic section — summaries never need to appear on page 2+, and no page-aware logic is needed beyond what already exists.

## Error handling

Unchanged from today's single-item behavior, just applied per-item: a failed `/summarize` call sets that item's entry to `''`, rendering no summary line and surfacing no error to the user.

## Testing

No frontend test suite exists in this repo (confirmed: no `*.test.*` files under `frontend/src/`). Verification is manual:
1. Run `frontend && npm run dev` against a running backend.
2. Perform a search that returns 5+ semantic results.
3. Confirm all 5 results in the top page show independent "Summarizing…" placeholders, each resolving to a distinct summary as its request completes (not all appearing simultaneously, if response times differ).
4. Confirm paging away and back doesn't refetch or lose already-resolved summaries (state lives in `App.tsx`, unaffected by `ResultSection`'s internal `page` state).
5. Confirm a fresh search resets all 5 summary slots (no stale summaries from the previous query).

## Out of scope

- No backend/`SummarizeClient` changes.
- No concurrency cap on the 5 requests (see design discussion — rejected as unnecessary for a single-user tool).
- No batch summarization endpoint.
