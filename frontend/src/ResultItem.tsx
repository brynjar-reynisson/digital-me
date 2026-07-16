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
