import { useState } from 'react'
import './App.css'
import { SearchResult, SearchResponse, SummarizeResponse } from './types'
import { SearchBar } from './SearchBar'
import { ResultSection } from './ResultSection'

const PAGE_SIZE = 10
const SEMANTIC_PAGE_SIZE = 5

function App() {
  const [keywords, setKeywords] = useState('')
  const [semanticResults, setSemanticResults] = useState<SearchResult[]>([])
  const [keywordResults, setKeywordResults] = useState<SearchResult[]>([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [summaries, setSummaries] = useState<Record<string, string | null>>({})
  const [searchId, setSearchId] = useState(0)

  function fetchSummary(source: string, snippet: string) {
    fetch('/summarize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: snippet, source }),
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

        const topResults = results.slice(0, SEMANTIC_PAGE_SIZE)
        const loadingEntries: Record<string, null> = {}
        for (const item of topResults) {
          if (item.snippet) {
            loadingEntries[item.source] = null
            fetchSummary(item.source, item.snippet)
          }
        }
        setSummaries(s => ({ ...s, ...loadingEntries }))
      })
      .catch(e => {
        console.error('Semantic search failed', e)
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

