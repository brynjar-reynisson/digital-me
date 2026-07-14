# Faster summarization via DeepSeek (through opencode CLI)

## Problem

On-demand summarization (`POST /summarize`, called by the frontend after semantic search) currently goes through `OllamaSummarizeClient`, which is slow. The user already has DeepSeek API credits wired into the `opencode` CLI and wants to use `opencode run --model deepseek/deepseek-v4-flash --format json "<prompt>"` as a faster summarization backend.

## Scope

Only the summarization path (`SummarizeClient` → `/summarize` endpoint) changes. Embeddings/semantic search continue to use Ollama (`nomic-embed-text`) unchanged.

## Design

### Provider selection

A new property `summarize.provider` selects which `SummarizeClient` bean is active:
- `deepseek` (default, `matchIfMissing = true`)
- `ollama`

Both `OllamaSummarizeClient` and the new `DeepseekSummarizeClient` are `@Component`-annotated with `@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "...", ...)`. No changes needed in `AppConfig` — Spring picks whichever bean matches.

### `DeepseekSummarizeClient` (new, package `com.breynisson.router.mcp`)

Implements `SummarizeClient`.

Constructor config (via `@Value`, all with defaults so no properties file changes are required to run):
- `opencode.command` (default `opencode`)
- `opencode.summarize.model` (default `deepseek/deepseek-v4-flash`)
- `opencode.summarize.timeout-seconds` (default `60`)
- `ObjectMapper` (existing Spring bean, reused for JSON parsing)

`summarize(text)`:
1. Build the same prompt template `OllamaSummarizeClient` uses: `"Summarize the following in 2-3 sentences:\n\n" + text`.
2. Run `ProcessBuilder(opencodeCommand, "run", "--model", model, "--format", "json", prompt)` — a real argument list, not a shell string. The JDK's Windows process creation resolves a bare command name to `opencode.cmd` via PATH and escapes each argument itself; no manual `cmd.exe /c` wrapping is used, avoiding a metacharacter-injection risk from summarized text that can originate from arbitrary web pages (via the Chrome extension) and might contain shell metacharacters (`&`, `|`, `^`, etc.).
3. Read stdout fully; wait up to `timeoutSeconds` via `Process.waitFor(timeout, TimeUnit.SECONDS)`. If it times out, `destroyForcibly()` and return `null`. If exit code is non-zero, log a warning (including captured stderr) and return `null`.
4. Parse stdout via the static helper `extractSummary` (below). Return its result (or `null`).

`isAvailable()`: runs `[opencodeCommand, "--version"]` with a short timeout (5s); returns `true` iff exit code is 0. Mirrors `OllamaSummarizeClient.isAvailable()`, which also just checks reachability, not that a model/credentials are fully working.

### NDJSON parsing

`opencode run --format json` streams newline-delimited JSON events. Confirmed by manual invocation:
```
{"type":"step_start", ...}
{"type":"text", ..., "part":{..., "type":"text", "text":"<summary text>", ...}}
{"type":"step_finish", ...}
```

Static, package-visible method:
```java
static String extractSummary(String stdout, ObjectMapper objectMapper)
```
- Splits `stdout` into lines.
- For each line, tries to parse as JSON; malformed/partial lines are skipped (no exception propagates).
- Whenever a parsed line's top-level `type` field is `"text"`, reads `part.text` and overwrites a running `result` variable (so if multiple text events occur, the *last* one wins).
- Returns the final `result` (stripped), or `null` if no `"text"` line was found.

This method contains all the parsing logic and is unit-testable without spawning a real process.

### Configuration

Added to `application.properties` (commented out, matching the existing Ollama block style):
```properties
# summarize.provider=deepseek
# opencode.command=opencode
# opencode.summarize.model=deepseek/deepseek-v4-flash
# opencode.summarize.timeout-seconds=60
```

## Testing

`DeepseekSummarizeClientTest` unit-tests only `extractSummary` with canned NDJSON strings:
- Single `"text"` line → returns the trimmed text.
- Multiple `"text"` lines → returns the last one.
- A malformed/garbage line mixed in among valid lines → ignored, valid lines still parsed.
- No `"text"` line present → returns `null`.

No real `opencode`/DeepSeek invocation happens in tests (no API cost, no dependency on `opencode` being installed in CI), matching the existing convention of mocking the external dependency (`EmbeddingClient` lambdas, `OllamaEmbeddingClientTest`'s mock `HttpServer`).

Manual end-to-end verification (not an automated test) will be done once during implementation: start the app with `summarize.provider` at its default, hit `/summarize` with real text, and confirm a real DeepSeek-produced summary comes back.

## Docs

Update `docs/architecture.md`'s `SummarizeClient` / `OllamaSummarizeClient` section to describe the provider toggle and add a `DeepseekSummarizeClient` bullet.

## Out of scope

- No changes to embeddings/semantic search (still Ollama/`nomic-embed-text`).
- No removal of `OllamaSummarizeClient` — it remains available via `summarize.provider=ollama`.
- No MCP-facing changes (`/summarize` is a REST-only endpoint, not exposed as an MCP tool).
