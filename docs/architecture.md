# Architecture

## REST API (`IndexPage.java`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Redirects to `/index.html` |
| `GET` | `/search?keywords=...` | Lucene full-text search; returns `{ results: [{source, name}] }` |
| `GET` | `/semanticSearch?keywords=...` | Semantic search via Ollama embeddings; returns `{ results: [{source, name, snippet}] }` |
| `POST` | `/summarize` | On-demand text summarization; body: `{ text }`; returns `{ summary }` |
| `GET` | `/localFile?filePath=...` | Reads a local file and returns HTML-escaped content |
| `POST` | `/addContent` | Indexes content; body: `{ source, name, content }` |

`/addContent` uses a `ReentrantLock` for thread safety. If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing.

---

## Camel routes

Routes are XML files in `digital-me-dev/routes/` loaded at runtime via:
```
camel.springboot.routes-include-pattern = file:./routes/*.xml
```
The app must be run with `digital-me-dev/` as the working directory so relative paths resolve correctly. Modifying route files does **not** require a rebuild — restart the JVM.

### `local-file-changes.xml` (active)
- `scheduler:file-change-watcher` fires every 5 seconds
- Calls `FileChangeWatcher.watchDirectory()` for configured paths
- `file:content-receive` polls the `content-receive/` directory and processes dropped files via `ContentReceive`

---

## Key subsystems

### `FileChangeWatcher`
- Only indexes `.txt` files (other extensions are ignored)
- Path with `/*` suffix triggers recursive subdirectory scanning (one level deep, then recurses)
- Compares file `lastModified` vs. DB `TIME` to skip unchanged files
- On new/changed file: calls `LuceneIndex.createOrUpdateIndex()` + `TextEntryDao.insert/update()`

### `LuceneIndex` (static utility class)
- Index path defaults to `./lucene-index/` but can be overridden via `LuceneIndex.setIndexPath()` (used in tests)
- At runtime resolves to `digital-me-dev/lucene-index/`
- Document key field: `source` — delete-then-reinsert on update
- Stored fields: `source` (StringField), `name` (StringField), `body` (TextField)
- `find()` uses `QueryParser` on the `body` field, returns up to 1,000,000 hits
- `deleteIndex()` deletes all files in the index dir (used in tests)

### `DatabaseAdapter` (static utility class)
- Singleton SQLite connection; reopens if closed
- `init()` must be called at startup — runs numbered migration scripts from classpath (`digital-me-db-N.sql`)
- Migration tracking: `APPLICATION_METADATA` table with `database.version` key
- To add a migration: create `src/main/resources/digital-me-db-5.sql` (next number after the existing four — check the highest existing `digital-me-db-N.sql` before picking a number)
- `setDefaultDatabasePath()` also closes and nulls the current connection, so the new path takes effect immediately

### `TextEntryDao`
- `NAME` column stores the file absolute path or URL (`source`)
- `findByName(source)` is used to check if an entry exists before insert vs. update

### `EmbeddingClient` (functional interface)
- Single method: `float[] embed(String text)` — returns `null` when Ollama is unavailable
- Used as a lambda throughout; tests pass `text -> null` (unavailable) or `text -> new float[]{...}` (mock)

### `OllamaEmbeddingClient`
- Posts to `http://localhost:11434/api/embeddings` with model `nomic-embed-text`
- **Normalises text** before embedding: replaces literal `\\n`, `\\t`, `\\r` (Chrome extension artifact) with spaces, collapses whitespace
- **Caps at 3000 chars** after normalisation (nomic-embed-text has 2048-token context; Chrome extension content can be token-dense)
- Sends `options.num_ctx=2048` in every request to set the model context window
- Returns `null` on HTTP error or connection failure (caller skips indexing gracefully)

### `EmbeddingIndex`
- Runs `indexAll()` on a daemon thread at startup: loads already-indexed file paths from DB (one SELECT), then walks `mcp-resources/` and indexes new files only
- `indexFile(path)`: reads the file, splits the body into ~2000-char sentence-boundary-aware chunks via `Chunker`, embeds each chunk (prefixed with `ollama.embedding.document-prefix`), stores one row per chunk in `MCP_EMBEDDING` with a unit-normalized vector, and adds each to the in-memory cache
- `findSimilar(query, topK)`: embeds the (prefixed) query, scores every cached chunk vector via dot product, drops chunks below `semantic-search.min-score`, dedups to each file's best-scoring chunk, and returns the top-K `ScoredResult` records (`filePath`, `sourceUrl`, `score`, `chunkText`)
- `indexAll()` additionally reconciles the table on each run: deletes rows for files no longer on disk, and deletes rows whose `MODEL` doesn't match the currently configured `ollama.embedding.model` (both get re-embedded on the same pass)

### `McpEmbeddingDao`
- `upsert(McpEmbedding)` — INSERT OR REPLACE into `MCP_EMBEDDING`, keyed by `(FILE_PATH, CHUNK_INDEX)`
- `findAll()` — returns list of `McpEmbedding` (reads FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING columns; MODEL/INDEXED_AT come back null, not needed for search)
- `findAllFilePaths()` — returns `Set<String>` of already-indexed paths, `SELECT DISTINCT` since multiple chunk rows share a file path
- `deleteByFilePath(filePath)` — deletes all chunk rows for a file (used to reconcile deleted files)
- `deleteByModelNot(currentModel)` — deletes rows whose `MODEL` doesn't match the currently configured embedding model

### `SemanticSearch`
- Spring `@Component` combining `EmbeddingIndex` + `SummarizeClient`
- `search(query)`: calls `EmbeddingIndex.findSimilar(query, FINAL_RESULT_LIMIT=50)`, filters via `ExclusionRules`, returns list of `{source, name, snippet}` maps with the snippet built from the winning chunk's text
- `summarize(text)`: delegates to `SummarizeClient`; returns null when the backend is unavailable
- `snippet(raw)` (static): strips first line (source URL), normalises whitespace, caps at 2000 chars; appends `<truncated, use fetch tool>` if truncated — used by the keyword-search fallback, which still reads whole files
- `chunkSnippet(chunkText)` (static): same normalisation/truncation as `snippet()` but without stripping a header line, since chunk text has no source-URL header — used by semantic search results

### `ExclusionRules`
- Static utility; `isExcluded(url)` returns true for: null, localhost:3001, localhost:8080, google domains, islandsbanki, facebook.com, quora.com, meta.com/is
- Applied in both `SemanticSearch.search()` and `McpServerConfig` keyword search to filter noise

### `SummarizeClient` (functional interface)
- Single method: `String summarize(String text)` — returns `null` when the backend is unavailable
- Used as a lambda in tests; two production implementations exist, selected via `summarize.provider` (`deepseek` default, `matchIfMissing = true`; or `ollama`), each `@ConditionalOnProperty`-gated so exactly one is registered as a Spring bean

### `DeepseekSummarizeClient`
- Default summarization backend; shells out to the `opencode` CLI: `opencode run --model <model> --format json "<prompt>"` (model configurable via `opencode.summarize.model`, default `deepseek/deepseek-v4-flash`)
- `opencode.command` defaults to `opencode.cmd`, not `opencode` — on Windows, `ProcessBuilder` does not do `cmd.exe`-style PATHEXT resolution of bare command names, so the npm `.cmd` shim must be named explicitly
- Sends the same "Summarize in 2-3 sentences" instruction as `OllamaSummarizeClient`, but with a single-line `": "` separator instead of `":\n\n"` — `opencode.cmd` runs through `cmd.exe`, which truncates an argument at an embedded newline
- Since `text` can originate from arbitrary scraped web pages and becomes a `cmd.exe`-routed argument, `sanitizeArgument()` strips characters `cmd.exe` treats specially (`& | < > ^ " %`, plus CR/LF) before building the prompt
- Immediately closes the child process's stdin after starting it — `opencode` blocks reading stdin until EOF, and `ProcessBuilder` otherwise leaves it open indefinitely
- Parses the CLI's newline-delimited JSON stdout via the static `extractSummary()` method, taking the last `"type":"text"` event's `part.text`
- Times out after `opencode.summarize.timeout-seconds` (default 60s), destroying the process and returning `null` (stdout is drained on a background thread so the timeout isn't defeated by a blocking read)
- `isAvailable()` runs `opencode --version` and checks for a zero exit code

### `OllamaSummarizeClient`
- Alternate summarization backend, enabled via `summarize.provider=ollama`
- Posts to `http://localhost:11434/api/generate` with model configurable via `ollama.summarize.model` (default: `llama3.2`)
- Sends a "Summarize in 2-3 sentences" prompt; 120-second timeout
- Returns `null` on HTTP error or connection failure

### `YouTubeCaptionExtractor`
- Located in `extract/` package
- `extractFromYouTubeUrl(url)`: parses `v=` query param, calls `extract(videoId)`
- `extract(videoId)`: uses `youtube-transcript-api` library; returns timed transcript lines as `[start_sec] text\n`

---

## Database schema

```sql
APPLICATION_METADATA (KEY PK, VALUE)   -- stores database.version
TEXT_ENTRY (UUID PK, TIME, NAME)        -- indexed content entries
TEXT_ENTRY_METADATA (TEXT_ENTRY_UUID, KEY, VALUE, PK composite)
MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING BLOB, MODEL, INDEXED_AT, PK(FILE_PATH, CHUNK_INDEX))  -- chunked vector embeddings
```

`TIME` and `INDEXED_AT` are stored as ISO-8601 instant strings (e.g. `2024-01-15T10:30:00Z`).
`EMBEDDING` is a raw `BLOB` of packed IEEE 754 floats (4 bytes each, big-endian via `ByteBuffer`).

---

## Frontend (`frontend/`)

- Single `App.jsx` component — no router
- Two result sections displayed side-by-side after search:
  - **Semantic Search Results**: calls `/semanticSearch`; 5 results per page (`SEMANTIC_PAGE_SIZE = 5`)
  - **Keyword Search Results**: calls `/search`; 10 results per page (`PAGE_SIZE = 10`)
- Both searches run in parallel via `Promise.all`
- **On-demand summarization**: after semantic search, the top result's snippet is POSTed to `/summarize`; the summary is displayed below that result while loading ("Summarizing…")
- Local file results: linked to `/localFile?filePath=<encoded-path>`
- Web results: linked directly to the URL
- Labels truncated to 90 characters in the result list

---

## Chrome extension (`chrome-extension/`)

- Manifest V3, permissions: `activeTab`, `tabs`, `webNavigation`, `scripting`
- `content-script.js` runs on all `http://` and `https://` pages
- `background.js` (service worker) POSTs page content to `http://localhost:8080/addContent`

---

## Actuator endpoints

Exposed at `/actuator`:
- `health` — `GET /actuator/health`
- `info` — `GET /actuator/info`
- `camelroutes` — `GET /actuator/camelroutes` (read-only)
