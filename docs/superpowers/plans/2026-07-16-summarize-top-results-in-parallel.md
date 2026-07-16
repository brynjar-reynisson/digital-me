# Summarize Top 5 Semantic Results In Parallel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing on-demand `/summarize` flow so all 5 results on the first semantic-search page get a summary — not just result #1 — fetched concurrently so each fills in independently as its request completes.

**Architecture:** Replace the single `topSummary` piece of state in `App.tsx` with a map from result `source` → summary state (`undefined` not requested / `null` loading / `string` resolved). After semantic results arrive, fire one `POST /summarize` per top-5 result with a snippet, each updating only its own map entry on resolution. `ResultSection` and `ResultItem` are simplified to look the summary up by `source` instead of an index-based `isTop` flag. No backend changes.

**Tech Stack:** React 19 + TypeScript 5 (strict), Vite 7 dev server proxying to the Spring Boot backend on `localhost:8080`.

## Global Constraints

- No backend/`SummarizeClient` changes — `POST /summarize` is already stateless and reentrant (per spec `docs/superpowers/specs/2026-07-16-summarize-top-results-in-parallel-design.md`).
- No concurrency cap on the 5 requests.
- `SEMANTIC_PAGE_SIZE` stays `5` — the top 5 results are exactly page 1 of the semantic section, so no page-aware summary logic is needed.
- Per project workflow rule (`CLAUDE.md`): run `/simplify` after changing source files, before committing.
- Frontend has no automated test suite (no `*.test.*` files, no test runner in `frontend/package.json`) — verification is `tsc --noEmit` (via `npm run build`), `npm run lint`, and manual browser testing, not unit tests.

---

### Task 1: Rework ResultItem/ResultSection/App to fetch and render 5 parallel summaries

**Files:**
- Modify: `frontend/src/ResultItem.tsx`
- Modify: `frontend/src/ResultSection.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Produces: `ResultItemProps { item: SearchResult; summaries?: Record<string, string | null> }` (replaces `isTop`/`topSummary`)
- Produces: `ResultSectionProps { title: string; results: SearchResult[]; summaries?: Record<string, string | null>; pageSize: number }` (replaces `topSummary`)
- Produces: `App`'s `summaries` state: `Record<string, string | null>`, keyed by `SearchResult.source`

These three files change together — `ResultSection`/`App` cannot type-check against the old `ResultItem` props once `ResultItem`'s interface changes, so implement all three before running the build check in Step 5.

- [ ] **Step 1: Rewrite `frontend/src/ResultItem.tsx` to key its summary off the shared map**

```tsx
import { SearchResult } from './types'
import { buildHref, truncateLabel } from './utils'

interface ResultItemProps {
  item: SearchResult
  summaries?: Record<string, string | null>
}

export function ResultItem({ item, summaries }: ResultItemProps) {
  const label = item.displayName || item.name || item.source
  const display = truncateLabel(label)
  const scorePercent = item.score ? Math.round(item.score * 100) : null
  const frequencies = item.termFrequencies 
    ? Object.entries(item.termFrequencies)
        .map(([term, count]) => `${term} x${count}`)
        .join(', ')
    : null
  const summary = summaries?.[item.source]
  const hasSummary = summary !== undefined

  return (
    <li>
      <div className="result-header">
        <a href={buildHref(item.source)} target="_blank" rel="noopener noreferrer">
          {display}
        </a>
        {" "}
        {scorePercent !== null && !item.termFrequencies && (
          <span className="result-score" title={`Similarity score: ${item.score}`}>
            {scorePercent}%
          </span>
        )}
      </div>
      {hasSummary && summary === null && (
        <p className="result-summary result-summary--loading">Summarizing…</p>
      )}
      {hasSummary && summary && (
        <p className="result-summary">{summary}</p>
      )}
      {!hasSummary && item.snippet && !item.score && (
        <p 
          className="result-snippet" 
          dangerouslySetInnerHTML={{ __html: item.snippet }} 
        />
      )}
      {frequencies && (
        <p className="result-frequencies">
          {frequencies}
        </p>
      )}
    </li>
  )
}
```

Note: `hasSummary` replaces the old `isTop` flag as the "suppress inline snippet, show summary instead" signal. Behavior for keyword-search items (which have no `score` and are never in `summaries`) and semantic items beyond the top 5 (never in `summaries`) is unchanged from today.

- [ ] **Step 2: Rewrite `frontend/src/ResultSection.tsx` to drop index-based `isTop` and pass the map straight through**

```tsx
import { useState } from 'react'
import { SearchResult } from './types'
import { ResultItem } from './ResultItem'

interface ResultSectionProps {
  title: string
  results: SearchResult[]
  summaries?: Record<string, string | null>
  pageSize: number
}

export function ResultSection({ title, results, summaries, pageSize }: ResultSectionProps) {
  const [page, setPage] = useState(0)

  const totalPages = Math.ceil(results.length / pageSize)
  const pageResults = results.slice(page * pageSize, page * pageSize + pageSize)

  if (results.length === 0) return null

  return (
    <div className="result-section">
      <h2 className="result-section-title">{title}</h2>
      <p className="result-count">
        {results.length} result{results.length !== 1 ? 's' : ''}
        {totalPages > 1 && ` — page ${page + 1} of ${totalPages}`}
      </p>
      <ul>
        {pageResults.map((item) => (
          <ResultItem 
            key={item.source} 
            item={item} 
            summaries={summaries} 
          />
        ))}
      </ul>
      {totalPages > 1 && (
        <div className="pagination">
          <button onClick={() => setPage(p => p - 1)} disabled={page === 0}>
            ← Previous
          </button>
          <span>{page + 1} / {totalPages}</span>
          <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}>
            Next →
          </button>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 3: Rewrite `frontend/src/App.tsx` to fetch 5 summaries in parallel into the map**

```tsx
import { useState } from 'react'
import './App.css'
import { SearchResult, SearchResponse, SummarizeResponse } from './types'
import { SearchBar } from './SearchBar'
import { ResultSection } from './ResultSection'

const PAGE_SIZE = 10
const SEMANTIC_PAGE_SIZE = 5
const SUMMARY_COUNT = 5

function App() {
  const [keywords, setKeywords] = useState('')
  const [semanticResults, setSemanticResults] = useState<SearchResult[]>([])
  const [keywordResults, setKeywordResults] = useState<SearchResult[]>([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [summaries, setSummaries] = useState<Record<string, string | null>>({})
  const [searchId, setSearchId] = useState(0)
  const [semanticError, setSemanticError] = useState<string | null>(null)

  function fetchSummary(source: string, snippet: string) {
    fetch('/summarize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: snippet }),
    })
      .then(r => r.json() as Promise<SummarizeResponse>)
      .then(d => setSummaries(s => ({ ...s, [source]: d.summary || '' })))
      .catch(() => setSummaries(s => ({ ...s, [source]: '' })))
  }

  async function doSearch() {
    const trimmed = keywords.trim()
    if (!trimmed) return
    setLoading(true)
    setError(null)
    setSemanticError(null)
    setSummaries({})
    setSearchId(id => id + 1)
    
    const encoded = encodeURIComponent(trimmed)

    // Run searches in parallel but handle them independently
    const semanticPromise = fetch('/semanticSearch?keywords=' + encoded)
      .then(async res => {
        if (!res.ok) throw new Error('Semantic search returned ' + res.status)
        const data = await res.json() as SearchResponse
        const results = data.results || []
        setSemanticResults(results)

        const topResults = results.slice(0, SUMMARY_COUNT)
        const loadingEntries: Record<string, null> = {}
        for (const item of topResults) {
          if (item.snippet) loadingEntries[item.source] = null
        }
        setSummaries(s => ({ ...s, ...loadingEntries }))
        for (const item of topResults) {
          if (item.snippet) fetchSummary(item.source, item.snippet)
        }
      })
      .catch(e => {
        console.error('Semantic search failed', e)
        setSemanticError(e.message)
        setSemanticResults([])
      })

    const keywordPromise = fetch('/search?keywords=' + encoded)
      .then(async res => {
        if (!res.ok) throw new Error('Keyword search returned ' + res.status)
        const data = await res.json() as SearchResponse
        setKeywordResults(data.results || [])
      })
      .catch(e => {
        console.error('Keyword search failed', e)
        setError(e.message)
        setKeywordResults([])
      })

    try {
      await Promise.all([semanticPromise, keywordPromise])
      setSearched(true)
    } finally {
      setLoading(false)
    }
  }

  const totalResults = semanticResults.length + keywordResults.length

  return (
    <div className={searched ? 'app' : 'app app--centered'}>
      <h1 className="app-title">Digital Me</h1>
      <SearchBar 
        keywords={keywords} 
        setKeywords={setKeywords} 
        onSearch={doSearch} 
        loading={loading} 
      />

      {error && <p className="error">Error: {error}</p>}

      {searched && !loading && (
        <div className="results">
          {totalResults === 0 ? (
            <p className="no-results">No results for <strong>{keywords}</strong>.</p>
          ) : (
            <>
              <ResultSection 
                key={`semantic-${searchId}`}
                title="Semantic Search Results" 
                results={semanticResults} 
                summaries={summaries} 
                pageSize={SEMANTIC_PAGE_SIZE} 
              />
              <ResultSection 
                key={`keyword-${searchId}`}
                title="Keyword Search Results" 
                results={keywordResults} 
                pageSize={PAGE_SIZE}
              />
            </>
          )}
        </div>
      )}
    </div>
  )
}

export default App
```

- [ ] **Step 4: Run `/simplify`**

Per project workflow rule, invoke the `/simplify` slash command now that source files have changed, and apply any cleanup it suggests before continuing.

- [ ] **Step 5: Type-check and lint**

Run: `cd frontend && npm run build`
Expected: completes with no `tsc` errors (this repo's `build` script is `tsc --noEmit && vite build`), producing a `dist/` bundle.

Run: `cd frontend && npm run lint`
Expected: no errors (pre-existing warnings, if any, unrelated to these three files are fine).

- [ ] **Step 6: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add frontend/src/ResultItem.tsx frontend/src/ResultSection.tsx frontend/src/App.tsx
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: summarize top 5 semantic results in parallel"
```

---

### Task 2: Update docs to describe the 5-result parallel summary behavior

**Files:**
- Modify: `docs/architecture.md` (Frontend section)

**Interfaces:**
- Consumes: nothing from Task 1's code (docs-only), but describes its behavior accurately.

- [ ] **Step 1: Update the "On-demand summarization" bullet in `docs/architecture.md`**

Find this bullet under `## Frontend (frontend/)`:

```markdown
- **On-demand summarization**: after semantic search, the top result's snippet is POSTed to `/summarize`; the summary is displayed below that result while loading ("Summarizing…")
```

Replace it with:

```markdown
- **On-demand summarization**: after semantic search, each of the top 5 results' snippets is POSTed to `/summarize` concurrently; each summary is displayed below its own result while loading ("Summarizing…"), independently of the others' completion order
```

- [ ] **Step 2: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C C:/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: describe parallel top-5 summarization behavior"
```

---

### Task 3: Manual end-to-end verification

**Files:** none (no code changes; this task only runs the app and observes behavior)

**Interfaces:**
- Consumes: the running backend's `/semanticSearch` and `/summarize` endpoints, and the frontend built/updated in Task 1.

- [ ] **Step 1: Start the backend**

From the project root (working directory must be `digital-me-dev/` per `docs/architecture.md`):
```bash
java -jar target/digital-me-0.1.jar
```
If `target/digital-me-0.1.jar` isn't built yet, build first: `mvn package` (see `docs/tooling.md` for the Maven invocation on this machine, since `mvn` is not on PATH).
Expected: Spring Boot starts and logs it is listening on port 8080.

- [ ] **Step 2: Start the frontend dev server**

```bash
cd frontend && npm run dev
```
Expected: Vite dev server starts (default `http://localhost:5173`), proxying `/search`, `/semanticSearch`, `/summarize` etc. to `localhost:8080`.

- [ ] **Step 3: Run a search and observe the top 5 semantic results**

In a browser, open the Vite dev server URL and search a term known to return 5+ semantic results (any term that returns hits today works, since this only changes summary count, not which results appear).

Expected, per the design's testing section:
- All 5 results on the Semantic Search Results page each show their own "Summarizing…" placeholder immediately after results render.
- Each placeholder is independently replaced by its own summary as that result's `/summarize` call resolves — not all 5 appearing simultaneously if response times differ.
- No result is stuck on "Summarizing…" indefinitely (a failed request should resolve to no summary line, not a stuck loading state).

- [ ] **Step 4: Verify pagination doesn't disturb resolved summaries**

Page away to page 2 of the Keyword Search Results (or any control that re-renders `ResultSection`) and back.
Expected: the 5 semantic summaries remain exactly as they were — no refetch, no flicker, no loss (summary state lives in `App.tsx`, unaffected by `ResultSection`'s internal `page` state).

- [ ] **Step 5: Verify a fresh search resets summaries**

Run a second, different search.
Expected: all "Summarizing…" placeholders reset and repopulate for the new query's top 5 — no stale summaries from the previous search are visible even momentarily attached to the wrong result.

- [ ] **Step 6: Stop both servers**

Stop the Vite dev server (Ctrl+C) and the backend JVM once verification is complete.
