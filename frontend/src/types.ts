export interface SearchResult {
  source: string
  name?: string
  displayName?: string
  snippet?: string
  score?: number
  termFrequencies?: Record<string, number>
}

export interface SearchResponse {
  results: SearchResult[]
}

export interface SummarizeResponse {
  summary: string
}
