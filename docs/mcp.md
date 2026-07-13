# MCP Server

Exposed at `POST /mcp` (Streamable HTTP transport, `HttpServletStreamableServerTransportProvider`).

## Capabilities

- **Resources** — lists and reads all files under `digital-me-dev/mcp-resources/<year-month>/`
- **Tools** — `search(keywords)` searches `mcp-resources/` files using semantic + keyword search; `fetch(filename)` returns the full content of a file by filename

## Search tool behaviour

1. Tries semantic search first via `SemanticSearch.search()` (top-10 results by cosine similarity, filtered via `ExclusionRules`)
2. Falls back to keyword scan if semantic search returns empty (e.g. Ollama unavailable): case-insensitive OR scan across all `.txt` files, also filtered via `ExclusionRules`
3. Each result includes `source` (URL), `name` (filename), and `snippet` — for semantic results, built directly from the matching chunk's text (`SemanticSearch.chunkSnippet()`); for the keyword fallback, the first 2000 chars of file content after the source URL line (`SemanticSearch.snippet()`), whitespace-normalised; truncated snippets include `<truncated, use fetch tool>` hint
4. Response is truncated at ~900 000 chars to avoid overwhelming the MCP client

## Fetch tool behaviour

- `fetch(filename)` — takes the `name` field from a search result and returns the full raw file content
- Searches all subdirectories of `mcp-resources/` for a matching filename
- Response capped at ~900 000 chars

## Key classes

- `McpServerConfig` — `@Configuration` that registers transport, servlet, and `McpSyncServer` beans; builds search, fetch, and resource handlers
- `SemanticSearch` — Spring component wrapping `EmbeddingIndex` + `SummarizeClient`; owns snippet extraction and exclusion filtering (see `docs/architecture.md`)
- `ResourceReceiver` — writes MCP-submitted content to `mcp-resources/<year-month>/<timestamp-name>.txt`; first line is source URL; triggers `EmbeddingIndex.indexFile()` after writing
- `EmbeddingIndex` — semantic vector search (see `docs/architecture.md`)

## Ollama setup

- Ollama must be running locally (`http://localhost:11434`)
- Required models:
  - `ollama pull nomic-embed-text` (274 MB) — for semantic search embeddings
  - `ollama pull llama3.2` — for on-demand summarization (default; configurable via `ollama.summarize.model`)
- If Ollama is unavailable the app still starts; search falls back to keyword scan and summarization returns empty

## Claude Desktop config

Config file location (Microsoft Store install):
`AppData\Local\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "digital-me": {
      "command": "npx",
      "args": ["mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

`mcp-remote` is required because Claude Desktop only supports stdio; it proxies to the HTTP endpoint.

## Compatibility notes

- Spring Boot 3.3.11 required (MCP SDK uses `jakarta.servlet`)
- `camel-xml-io-dsl-starter` required in Camel 4.x for XML route loading
- `lucene-backward-codecs:9.11.1` required to read old Lucene 8.7 (`Lucene87`) indexes
- `jackson-annotations:2.20` forced in `<dependencyManagement>` (MCP SDK's Jackson 3.x needs `JsonFormat.Shape.POJO`)
