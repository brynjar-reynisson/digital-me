import { useEffect, useState } from 'react'
import { IndexHealth, OllamaStatus } from './types'

interface IndexInfoModalProps {
  onClose: () => void
}

export function IndexInfoModal({ onClose }: IndexInfoModalProps) {
  const [health, setHealth] = useState<IndexHealth | null>(null)
  const [ollama, setOllama] = useState<OllamaStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([
      fetch('/health/index').then(r => r.json() as Promise<IndexHealth>),
      fetch('/health/ollama').then(r => r.json() as Promise<OllamaStatus>),
    ])
      .then(([healthData, ollamaData]) => {
        setHealth(healthData)
        setOllama(ollamaData)
      })
      .catch(() => setError('Could not load index health.'))
  }, [])

  function handleBackdropClick(e: React.MouseEvent<HTMLDivElement>) {
    if (e.target === e.currentTarget) onClose()
  }

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick}>
      <div className="modal-panel" role="dialog" aria-label="Index health">
        <div className="modal-header">
          <h2>Index Health</h2>
          <button className="modal-close" onClick={onClose} aria-label="Close" type="button">×</button>
        </div>

        {error && <p className="error">{error}</p>}

        {!error && !health && <p className="modal-loading">Loading…</p>}

        {health && (
          <>
            <div className="info-stats">
              <div className="info-stat">
                <span className="info-stat-value">{health.indexedFiles.toLocaleString()}</span>
                <span className="info-stat-label">Indexed Files</span>
              </div>
              <div className="info-stat">
                <span className="info-stat-value">{health.totalChunks.toLocaleString()}</span>
                <span className="info-stat-label">Total Chunks</span>
              </div>
              <div className="info-stat">
                <span className="info-stat-value">{health.totalFilesOnDisk.toLocaleString()}</span>
                <span className="info-stat-label">Files on Disk</span>
              </div>
            </div>

            <div className="coverage-bar-wrapper">
              <div className="coverage-bar-label">
                <span>Coverage</span>
                <span>{health.coveragePercent}%</span>
              </div>
              <div className="coverage-bar-track">
                <div
                  className="coverage-bar-fill"
                  style={{ width: `${Math.min(health.coveragePercent, 100)}%` }}
                />
              </div>
            </div>
          </>
        )}

        <div className="info-semantic-status">
          <span
            className={`ollama-status ollama-status--inline ${ollama?.online ? 'ollama-status--online' : 'ollama-status--offline'}`}
          />
          <span>Semantic search {ollama?.online ? 'active' : 'unavailable'}</span>
        </div>
      </div>
    </div>
  )
}
