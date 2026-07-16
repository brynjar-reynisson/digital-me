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
