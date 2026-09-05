# Fast, free summarization via Gemini, with DeepSeek fallback

## Problem

On-demand summarization (`POST /summarize`, called by the frontend after semantic search) currently goes through `DeepseekSummarizeClient` (via the `opencode` CLI), which takes ~12-14s per call and up to 60s under concurrent load. The goal is to speed this up without spending money.

Research into free summarization-specific APIs found none worth using — the ones with a hard small-input cap (e.g. 100 summaries/month) are too limited for daily use. The real win is a free, low-latency general-purpose LLM API. Google's Gemini free tier (`gemini-2.5-flash-lite`) offers sub-second-to-low-single-digit-second latency, no credit card, and ~1,000 requests/day / 15 requests/minute — an order of magnitude faster than the current path, and generous enough for a single-user tool.

Gemini's free tier is not limited by input *size* (comfortably handles the existing ~2000-char snippet cap and far more) — it's limited by request *rate*. So rather than routing by text length (the originally-considered "2KB cutoff" idea), Gemini becomes the default summarizer for all requests, falling back to DeepSeek only when Gemini fails, times out, or is rate-limited.

## Scope

Only the summarization path (`SummarizeClient` → `/summarize` endpoint) changes. Embeddings/semantic search continue to use Ollama (`nomic-embed-text`) unchanged. `OllamaSummarizeClient` remains available as a manual opt-out (`summarize.provider=ollama`) but is not part of the new default path.

## Design

### Bean wiring changes

Today, `DeepseekSummarizeClient` and `OllamaSummarizeClient` self-register as the single active `SummarizeClient` bean via `@ConditionalOnProperty`. That no longer works once the default path needs *two* clients composed together (Gemini primary + DeepSeek fallback), since Spring would then have two candidate `SummarizeClient` beans active at once (ambiguous injection into `SemanticSearch`/`IndexPage`).

Change:
- `DeepseekSummarizeClient`, `OllamaSummarizeClient`, and the new `GeminiSummarizeClient` become plain classes — remove `@Component` and `@ConditionalOnProperty`, and remove `@Value` from their constructors (values are now passed in directly by the caller).
- `AppConfig` gains a new `@Bean SummarizeClient summarizeClient(...)` factory method (alongside its existing bean registrations), taking all the `@Value`-sourced config as method parameters and constructing the right thing based on `summarize.provider` (default `gemini`):
  - `gemini` (default) → `new FallbackSummarizeClient(new GeminiSummarizeClient(...), new DeepseekSummarizeClient(...))`
  - `deepseek` → `new DeepseekSummarizeClient(...)`
  - `ollama` → `new OllamaSummarizeClient(...)`

This keeps exactly one `SummarizeClient` bean in the context at all times — no changes needed anywhere else that injects `SummarizeClient` (`SemanticSearch`, `IndexPage`).

### `GeminiSummarizeClient` (new, package `com.breynisson.router.mcp`)

Implements `SummarizeClient`. Same shape/contract as `OllamaSummarizeClient`: returns `null` on any failure, never throws.

Constructor params (plain, no `@Value` — supplied by `AppConfig`):
- `apiKey` (string; may be blank)
- `model` (default `gemini-2.5-flash-lite`)
- `timeoutSeconds` (default `20`)
- `ObjectMapper` (existing Spring bean, passed through)

`summarize(text)`:
1. If `apiKey` is blank, return `null` immediately (no doomed HTTP call — this alone is what makes the fallback engage when no key is configured).
2. POST to `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, with the API key sent via the `x-goog-api-key` header (never in the URL, so it never lands in server logs or referrer headers). Body: `{"contents":[{"parts":[{"text": "Summarize the following in 2-3 sentences:\n\n" + text}]}]}`.
3. Timeout: `timeoutSeconds` (default 20s) — generous versus Gemini's typical latency, but short enough that a hung/unreachable Gemini doesn't stall the DeepSeek fallback for long.
4. On non-200 (including 429 rate-limit) or any exception, log a warning (truncated body, no key) and return `null`.
5. On 200, parse `candidates[0].content.parts[0].text` and return it stripped; return `null` if that path is missing.

`isAvailable()`: same request shape as `summarize()` but with a trivial prompt, short timeout (5s); returns `true` iff HTTP 200. (Mirrors the "reachability check, not full correctness check" semantics `OllamaSummarizeClient.isAvailable()` already has.)

### `FallbackSummarizeClient` (new, package `com.breynisson.router.mcp`)

Implements `SummarizeClient`. Wraps a `primary` and `fallback` `SummarizeClient`.

- `summarize(text)`: call `primary.summarize(text)`; if the result is `null`, call `fallback.summarize(text)` and return that (which may itself be `null`).
- `isAvailable()`: `primary.isAvailable() || fallback.isAvailable()`.

No retry/backoff/caching logic of its own — caching per-source already happens one layer up in `SemanticSearch.summarize()`, and neither client here needs to know which of them actually produced the cached result.

### Configuration

Added to `application.properties` (commented out, matching the existing style):
```properties
# Summarization backend: "gemini" (default) tries Gemini first, falling back to DeepSeek
# on any Gemini failure/timeout/rate-limit; "deepseek" or "ollama" use only that backend.
# summarize.provider=gemini

# Gemini free-tier API key. Resolves GEMINI_API_KEY first, falling back to an existing
# GOOGLE_API_KEY (e.g. from another project) if you already have one set — if that key
# isn't valid for the Generative Language API, calls simply fail and fall back to
# DeepSeek, same as Gemini being unavailable. Get a free key at aistudio.google.com/apikey.
gemini.api.key=${GEMINI_API_KEY:${GOOGLE_API_KEY:}}
# gemini.summarize.model=gemini-2.5-flash-lite
# gemini.summarize.timeout-seconds=20

# opencode.command=opencode.cmd
# opencode.summarize.model=deepseek/deepseek-v4-flash
# opencode.summarize.timeout-seconds=60
```

Note `gemini.api.key`'s default line is *not* commented out (unlike the others) since it needs to actively resolve the env var fallback chain even when the user sets nothing else.

## Testing

- `GeminiSummarizeClientTest`: mocks the Gemini endpoint with the same JDK `com.sun.net.httpserver.HttpServer` pattern `OllamaEmbeddingClientTest` uses. Covers: successful response parsing, non-200 response → `null`, blank API key → `null` with no HTTP call made, malformed/missing `candidates` path → `null`.
- `FallbackSummarizeClientTest`: plain lambda `SummarizeClient`s per `docs/testing.md` convention (no Mockito). Covers: primary succeeds (fallback never called), primary returns `null` → fallback's result returned, both return `null` → `null`, `isAvailable()` true/false combinations.
- Existing `DeepseekSummarizeClientTest` unaffected (still tests only the static `extractSummary`/`sanitizeArgument` helpers).
- Manual end-to-end verification (not automated): start the app with `summarize.provider` at its default and a real key set, hit `/summarize`, confirm a Gemini-produced summary comes back quickly; then temporarily blank/break the key and confirm the DeepSeek fallback still produces a summary (slower, but working).

## Docs

- `docs/architecture.md`: update the `SummarizeClient`/`DeepseekSummarizeClient`/`OllamaSummarizeClient` bullets to describe the new `AppConfig`-driven wiring, and add `GeminiSummarizeClient`/`FallbackSummarizeClient` bullets.
- `application.properties`: add the config block above.

## Out of scope

- No text-length-based routing cutoff — Gemini's free tier isn't size-constrained the way the original "2KB" idea assumed; routing is purely success/failure based.
- No changes to embeddings/semantic search (still Ollama/`nomic-embed-text`).
- No removal of `OllamaSummarizeClient` — still available via `summarize.provider=ollama`.
- No MCP-facing changes (`/summarize` is a REST-only endpoint, not exposed as an MCP tool).
- No retry/backoff inside `GeminiSummarizeClient` beyond the single fallback hop — a rate-limited or erroring Gemini call fails once and defers to DeepSeek for that request; it doesn't retry Gemini.
