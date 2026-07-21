# Architecture

## REST API (`IndexPage.java`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Redirects to `/index.html` |
| `GET` | `/search?keywords=...` | Lucene full-text search; returns `{ results: [{source, name}] }` |
| `GET` | `/semanticSearch?keywords=...` | Semantic search via Ollama embeddings; returns `{ results: [{source, name, displayName, snippet}] }` — `name` is the internal mcp-resources filename, `displayName` (when present) is the original human-friendly name for display |
| `POST` | `/summarize` | On-demand text summarization; body: `{ text }`; returns `{ summary }` |
| `GET` | `/localFile?filePath=...` | Reads a local file; `.md`/`.markdown` files are rendered as formatted HTML via `MarkdownPageRenderer` (CommonMark GFM with tables/strikethrough/task-lists; raw HTML in source is escaped); all other file types return HTML-escaped plain text. `filePath` is normally an actual filesystem path, but semantic search results for synthetic sources (e.g. `claude://` Claude Code session URLs, which are never real paths) put that source identifier here instead — recognized by a non-`http(s)` URI scheme prefix and resolved via `McpEmbeddingDao.findFilePathsBySourceUrl()` to the indexed mcp-resources file. Since `filePath` is a raw caller-supplied query parameter, `resolveFilePath()` rejects it outright if it looks like a UNC/network path (`\\host\share\...`) — otherwise reading it would force Windows to open an SMB session to whatever host the caller names, leaking NTLM credentials — and otherwise requires it to already match a `TEXT_ENTRY` the app itself indexed (via `FileChangeWatcher` or `/addContent`), returning 403 for anything else; this narrows the endpoint from "read any file the JVM can access" down to "read files this app already knows about". The server also binds to `127.0.0.1` only (`server.address` in `application.properties`), since this is a documented single-user local tool with no reason to accept LAN connections |
| `POST` | `/addContent` | Durably queues content for async indexing; body: `{ source, name, content }`; returns `{ success: true }` as soon as the raw request is persisted to `ADD_CONTENT_QUEUE` — not once indexing has actually completed |

HTTP submissions to `/addContent` (Chrome extension, screenshot-OCR pipeline) are durably queued rather than processed inline: `IndexPage.addContent()` serializes the request to JSON, inserts it into the `ADD_CONTENT_QUEUE` table via `AddContentQueueDao`, and returns success immediately — the only durable step in the request path is that single SQLite insert, so a crash mid-request can lose at most one not-yet-queued submission rather than one mid-pipeline submission. `AddContentQueueProcessor` (`@Scheduled(fixedDelay = 2000)`) polls the queue and, for each entry, deserializes the payload and calls `DigitalMeStorage.addContent()` (the pipeline described below), deleting the row only after that call returns — so a crash mid-processing leaves the row for the next poll (after restart) to retry, the same principle `FileChangeWatcher` already relies on for its own re-scans. A payload that fails outright (e.g. corrupt JSON) increments an `ATTEMPTS` counter and is dropped with a logged error after 5 failed attempts rather than retried forever. `FileChangeWatcher` is unaffected by any of this — it still calls `DigitalMeStorage.addContent()` directly, in-process, since it's already self-healing via its own mtime-vs-`TEXT_ENTRY.TIME` comparison, independent of `addContent()`'s internals.

`DigitalMeStorage.addContent()` uses a `ReentrantLock` for thread safety around the synchronous write path (Lucene, `TEXT_ENTRY`, mcp-resources file). The embedding step (`embeddingIndex.indexFile()`) runs outside that lock on a dedicated bounded worker pool (`embedding.executor.pool-size`, default 1) instead of an unbounded `CompletableFuture.runAsync` — a burst of submissions (fast browsing, several screenshot-session flushes) queues up and drains one (or `pool-size` many) at a time rather than firing unbounded concurrent Ollama calls. Before anything else, `LocalFileEndpoint.isLocalFileUrl()` checks whether `source` is this app's own `/localFile?...` rendering endpoint (regardless of hostname/port, and regardless of whether the URL even has a scheme prefix) — if so, the submission is silently discarded (still returns success, nothing is written or indexed), preventing a feedback loop where the Chrome extension (or the screenshot-OCR pipeline, which can incidentally capture on-screen browser chrome mentioning a `/localFile` URL) re-captures already-indexed content as if it were new. If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing — unless `ScreenshotCoverage.isCovered()` determines the URL is a LinkedIn/Facebook/Quora/Google Docs page already captured more completely by the screenshot OCR pipeline, in which case the submission is silently discarded the same way. If a `PageHandler` in the `PageHandlers` registry matches the URL (e.g. `VisirPageHandler` for visir.is), its `extract()` result is used instead of the generic Jsoup strip; if that handler returns no extractable content, the submission is silently discarded the same way. Before any Jsoup parsing, `DefaultDigitalMeStorage.decodeIfJsonEncoded()` reverses one layer of JSON-string escaping the Chrome extension applies to page content (`content-script.js` does `JSON.stringify(document.body.innerHTML)`, and `background.js`'s own `JSON.stringify(request)` envelope means the server's single Jackson decode only removes the outer layer) — without this, HTML attribute values arrive escaped (e.g. `itemprop=\"articleBody\"`), which silently breaks any `PageHandler` selector that matches on attributes rather than plain text. The decode is a no-op for content that isn't JSON-string-shaped. If a matched handler's `extract()` returns `null` but the URL looks like an article (per that handler's `looksLikeArticleUrl()`), the submission falls back to the generic Jsoup strip instead of being discarded, and `LayoutChangeReporter` writes a one-per-site-per-month alert file to `<dataDir>/errors/` — see the `PageHandler` subsystem note below.

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
- Indexes `.txt`, `.md`, and `.pdf` files (other extensions are ignored). PDF content is extracted via PDFBox's `PDFTextStripper`; `.txt` and `.md` are read as plain text
- Path with `/*` suffix triggers recursive subdirectory scanning (one level deep, then recurses)
- Compares file `lastModified` vs. DB `TIME` to skip unchanged files. `watchDirectory(path)` loads the entire `TEXT_ENTRY` table into an in-memory `Map<name, TIME>` once via `TextEntryDao.findAllNameToTime()`, then reuses that map for every file in the (possibly recursive) scan — a per-file `findByName()` DB round trip here previously pegged a CPU core once a watched tree (e.g. an Obsidian vault) grew into the thousands of files, since `scheduler:file-change-watcher` reruns the full scan every 5 seconds indefinitely
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
- `findAllNameToTime()` — one `SELECT * FROM TEXT_ENTRY`, returns `Map<String, Instant>` (`NAME` → `TIME`); used by `FileChangeWatcher` to batch-load known entries once per directory scan instead of querying per file

### `AddContentQueueDao`
- `insert(payloadJson)` — inserts a new row with a generated UUID and `RECEIVED_AT` set to now, returns the UUID
- `findAllOrderedByReceivedAt()` — returns all pending entries oldest-first
- `delete(uuid)` — removes a processed (or dropped) entry
- `incrementAttempts(uuid)` — bumps the failure counter for a poison-pill payload

### `AddContentQueueProcessor`
- `@Scheduled(fixedDelay = 2000)` — polls `ADD_CONTENT_QUEUE`, deserializes each entry's JSON payload back into an `AddContentRequest`, and calls `DigitalMeStorage.addContent()`
- Deletes the row only after `addContent()` returns, so a crash mid-processing leaves it for the next poll (including after a restart) to retry
- A payload that fails to deserialize or otherwise throws increments `ATTEMPTS` via `AddContentQueueDao`; after 5 failed attempts the entry is logged at `ERROR` and dropped rather than retried forever

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
- `findSimilar(query, topK)`: embeds the (prefixed) query, scores every cached chunk vector via dot product, drops chunks below `semantic-search.min-score`, dedups to each **source URL's** best-scoring chunk (not each mcp-resources file path — a source resubmitted under a different physical file, whether a legitimate re-index or a duplicate capture, still collapses to one result), and returns the top-K `ScoredResult` records (`filePath`, `sourceUrl`, `score`, `chunkText`)
- `indexAll()` additionally reconciles the table on each run: deletes rows for files no longer on disk, and deletes rows whose `MODEL` doesn't match the currently configured `ollama.embedding.model` (both get re-embedded on the same pass)

### `McpEmbeddingDao`
- `upsert(McpEmbedding)` — INSERT OR REPLACE into `MCP_EMBEDDING`, keyed by `(FILE_PATH, CHUNK_INDEX)`
- `findAll()` — returns list of `McpEmbedding` (reads FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING columns; MODEL/INDEXED_AT come back null, not needed for search)
- `findAllFilePaths()` — returns `Set<String>` of already-indexed paths, `SELECT DISTINCT` since multiple chunk rows share a file path
- `deleteByFilePath(filePath)` — deletes all chunk rows for a file (used to reconcile deleted files)
- `deleteByModelNot(currentModel)` — deletes rows whose `MODEL` doesn't match the currently configured embedding model
- `findFilePathsBySourceUrl(sourceUrl)` / `deleteBySourceUrl(sourceUrl)` — used by `ResourceReceiver.deleteExistingFor()` (see below) to find and remove all chunk rows for a given source in one indexed lookup, rather than walking `mcp-resources/` on every submission

### `SummaryCacheDao`
- `find(sourceUrl)` — returns the cached summary for a source, or `null` if not cached
- `upsert(sourceUrl, summary)` — INSERT OR REPLACE into `SUMMARY_CACHE`, keyed by `SOURCE_URL`
- `deleteBySourceUrl(sourceUrl)` — called by `ResourceReceiver.deleteExistingFor()` and `ClaudeSessionIndexer`'s stale-file cleanup whenever a source's content is replaced, so a re-summarized file never serves a stale cached summary

### `SemanticSearch`
- Spring `@Component` combining `EmbeddingIndex` + `SummarizeClient`
- `search(query)`: calls `EmbeddingIndex.findSimilar(query, FINAL_RESULT_LIMIT=50)`, filters via `ExclusionRules`, returns `SearchResult`s with the snippet built from the winning chunk's text. `name` is the internal mcp-resources filename (required by the MCP fetch tool, see `docs/mcp.md`); `displayName` is the original human-friendly name, looked up in one batch via `LuceneIndex.findNamesBySources()` — Lucene already stores it correctly per source. Absent (falls back to `name`) for sources with no Lucene entry, e.g. Claude session transcripts
- `summarize(text, source)`: checks `SummaryCacheDao` for a cached summary for `source` first; on a miss, delegates to `SummarizeClient` and caches a non-null/non-empty result. Returns null when the backend is unavailable. `source` may be null, in which case the cache is never consulted or written (always calls `SummarizeClient`)
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

### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler` / `FotboltiPageHandler` / `CNNPageHandler` / `RedditPageHandler`
- Located in `extract/` package, alongside `YouTubeCaptionExtractor`
- `PageHandler` interface: `matches(url)` decides if a handler applies; `extract(Document)` returns the clean extracted text, or `null` to signal the submission has nothing worth indexing (discarded the same way as a `ScreenshotCoverage` match)
- `PageHandlers.find(url)` — static registry; returns the first matching handler, or empty if none apply (falls through to the generic Jsoup strip / YouTube extraction)
- `VisirPageHandler` — matches any `visir.is` URL; extracts the `h1` headline plus `div[itemprop=articleBody]` text, skipping all nav/related-article/footer markup. Returns `null` when no `articleBody` element is present, which covers the front page and other non-article pages (section fronts, live-blog hubs) without a separate root-URL check
- `DVPageHandler` — matches any `dv.is` URL; extracts the `h1` headline plus the direct-child `<p>` paragraphs of `div.article-body .field--name-body` (dv.is is Drupal-based, and `field--name-body` alone is not article-specific — it's reused for footer/sidebar widgets, so selection is scoped under the article-specific `div.article-body` wrapper, and direct-child-only paragraph selection excludes embedded image blocks). Returns `null` when no `article-body` element is present, covering the front page and other non-article pages
- `FotboltiPageHandler` — matches any `fotbolti.net` URL; extracts the `h1` headline plus all `div.font-body.text-base.leading-8` elements' joined text (fotbolti.net's CMS splits an article's body into two such divs around an inline "related article" card — this single selector matches both halves and nothing else on the page). Returns `null` when the selection is empty, which covers the front page and other non-article pages; unlike the other two handlers, there's no separate existence check — the extraction selector and the discard check are the same query
- `CNNPageHandler` — matches any `cnn.com` URL; if `div[itemprop=articleBody]` is present (standard articles), extracts the `h1` headline plus `p[data-component-name=paragraph]` elements scoped within it, excluding embedded related-content teaser cards (which don't carry that attribute). CNN live-blog pages (`/live-news/...`) don't use `articleBody` at all — their content is spread across many separate `live-story-post` blocks instead — so when `articleBody` is absent, the same paragraph selector is searched document-wide instead, picking up all of them. Returns `null` only when `articleBody` is absent and the document-wide selector also finds nothing, covering the front page and other non-content pages — if `articleBody` is present but happens to contain no matching paragraphs (e.g. a video-only article), the headline alone is still returned rather than `null`, matching the pre-live-blog-support behavior exactly. Does not capture subheadings (`data-component-name="subheader"` on standard articles, or each live-blog post's own `<h2>` sub-headline) — a known, accepted content-completeness limitation, same category as `FotboltiPageHandler`'s `<p>`-only extraction
- `RedditPageHandler` — matches any `reddit.com` URL; extracts the `h1[slot=title]` headline plus the post body (`div[property="schema:articleBody"]`) plus all comments (`shreddit-comment div[slot=comment]`, flattened without preserving reply nesting). Unlike every other handler, the post-body marker alone isn't a reliable discard signal — Reddit's feed pages (front page, subreddit/multireddit listings) render the same marker once per post-preview card — so the marker is scoped under `shreddit-post-text-body[view-context=CommentsPage]`, an attribute exclusive to the single post actually being viewed. Returns `null` when that scoped selector finds nothing, covering the front page and any listing page
- `looksLikeArticleUrl(url)` — cheap per-handler URL-shape heuristic (e.g. `VisirPageHandler` checks for `/g/`, `DVPageHandler` for a `/<id>/<yyyy>/<mm>/<dd>/` path, `FotboltiPageHandler` for `/news/`, `CNNPageHandler` for a `/<yyyy>/<mm>/<dd>/` path, `RedditPageHandler` for `/comments/`), consulted only when `extract()` returns `null`, to distinguish "legitimately not an article" (discard, unchanged) from "should be an article but the layout changed" (fall back + report)
- `siteName()` — short filename-safe slug (`visir`/`dv`/`fotbolti`/`cnn`/`reddit`) used by `LayoutChangeReporter`
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed

### `LayoutChangeReporter`
- Located in `digitalme/` package, alongside `DefaultDigitalMeStorage`
- `report(siteName, message)` writes `<dataDir>/errors/<year>-<month>-<day>-<hour>-<minute>-<second>-<siteName>.txt` with `message` as the file's content
- Throttled to once per site per calendar month: before writing, checks whether a file already exists in `errors/` matching `<year>-<month>-*-<siteName>.txt`; if so, silently no-ops. The check is filesystem-based, so it survives app restarts
- Called from `DefaultDigitalMeStorage.addContent()` when a matched handler's `extract()` returns `null` for a URL that `looksLikeArticleUrl()`

---

## Database schema

```sql
APPLICATION_METADATA (KEY PK, VALUE)   -- stores database.version
TEXT_ENTRY (UUID PK, TIME, NAME)        -- indexed content entries
TEXT_ENTRY_METADATA (TEXT_ENTRY_UUID, KEY, VALUE, PK composite)
MCP_EMBEDDING (FILE_PATH, CHUNK_INDEX, SOURCE_URL, CHUNK_TEXT, EMBEDDING BLOB, MODEL, INDEXED_AT, PK(FILE_PATH, CHUNK_INDEX))  -- chunked vector embeddings
SUMMARY_CACHE (SOURCE_URL PK, SUMMARY, CREATED_AT)  -- cached on-demand summaries, discarded when a source's content is replaced
ADD_CONTENT_QUEUE (UUID PK, PAYLOAD, RECEIVED_AT, ATTEMPTS)  -- durable queue for HTTP /addContent submissions, drained by AddContentQueueProcessor
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
- **On-demand summarization**: after semantic search, each of the top 5 results' snippets is POSTed to `/summarize` concurrently; each summary is displayed below its own result while loading ("Summarizing…"), independently of the others' completion order
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

---

## Screenshot OCR capture (`scripts/`)

`scripts/screenshot-capture.py` watches the active foreground browser window (Chrome/Edge/Firefox/Opera/Brave) and, when it's a recognized site (LinkedIn, Quora, Facebook, Google Docs — see `SITE_KEYWORDS`), captures and OCRs its content into digital-me via `/addContent`.

- **Capture:** `take_screenshot_bmp()` uses the Win32 `PrintWindow` API (`PW_RENDERFULLCONTENT`) to render directly from the target window's own surface, rather than copying on-screen pixels — this makes it immune to other windows visually overlapping the target at capture time.
- **Crop:** for Quora/LinkedIn/Facebook/Google Docs, `get_main_content_rect()` uses UI Automation to find the page's main content landmark (falling back to the whole document if none is found) and crops to it, via `content_rect_to_crop_box()`.
- **Google Docs document gating:** unlike the other three sites, `docs.google.com` matches on window title alone (`SITE_KEYWORDS`), which can't distinguish an open document from the Docs homepage/file-picker — both have "Google Docs" in the title. `is_non_document_google_docs_page()` closes that gap using the address-bar URL (Chrome/Edge only): if the URL is readable and doesn't contain `/document/d/`, the capture is skipped. If the URL can't be read at all, capture falls back to title-only (same graceful-degradation shape as `SUBPAGE_GATED_SITES`).
- **OCR:** `run_ocr()` / `run_ocr_lines()` use Tesseract (`pytesseract`) with a fixed `"isl+eng"` combined language model, after a grayscale + 2x upscale preprocessing step (`preprocess_for_ocr()`) tuned for small screen-rendered UI text. Requires Tesseract installed via `winget install --id UB-Mannheim.TesseractOCR -e` with both `eng.traineddata` and `isl.traineddata` present in its `tessdata` folder.
- **Line filtering:** when no landmark crop was found (`needs_line_filtering`), `run_ocr_filtered()` calls `run_ocr_lines()` to get per-line `(left, top, text)` positions, then `find_gap_threshold()` + `filter_and_sort_lines()` drop sidebar/nav text by finding the horizontal gap between the sidebar and main content columns.
- **Dedup:** `screenshot-capture-state.json` tracks the last screenshot's hash and OCR'd text; unchanged captures are skipped rather than re-sent.
- **Extension overlap:** the Chrome extension's plain-text page captures for LinkedIn/Facebook/Quora/Google Docs URLs already covered by this pipeline are discarded server-side by `ScreenshotCoverage.isCovered()` (see the `/addContent` description above) — it mirrors this script's own subpage-gating rules (facebook and google docs: any page; linkedin/quora: only root or their specific exempt subpath) so pages this script skips (e.g. individual LinkedIn articles, Quora answers) still get stored from the extension. Google Docs is covered unconditionally like Facebook, not gated like LinkedIn/Quora — the extension's canvas-rendered capture of a Docs page is expected to be near-empty regardless of whether it's the homepage or an open document, so there's no "safe" Docs subpath worth preserving from the extension.
- **Session consolidation:** consecutive captures of the same page are merged into one buffered session rather than sent individually. A session is keyed by the exact URL (`get_address_bar_url()`, chrome/edge only) or, when the URL can't be read, the site name. Each capture's OCR lines are appended to the session's line buffer with exact-string dedup (`merge_session_lines()`), so scrolled-past content accumulates in one place instead of being spread across many overlapping files. A session flushes (single `/addContent` POST via `flush_session()`) when a capture resolves to a different session key, or — as a safety net, checked every run via `check_idle_flush()` — when `IDLE_TIMEOUT_SECONDS` (120s) pass with no capture refreshing it, so a session isn't stranded if the browser is closed mid-session.
- **Watcher loop:** `screenshot-capture.ps1` re-runs the script every 3 seconds, restarting itself if killed.
