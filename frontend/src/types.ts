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

export interface OllamaStatus {
  online: boolean
  embedding: boolean
  summarize: boolean
}

export interface IndexHealth {
  indexedFiles: number
  totalChunks: number
  totalFilesOnDisk: number
  coveragePercent: number
}
