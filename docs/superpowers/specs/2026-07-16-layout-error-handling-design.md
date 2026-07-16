# Design: fall back and report when a `PageHandler`'s expected layout is gone

## Problem

`digital-me` has three `PageHandler` implementations (`VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`). Each one anchors its extraction on specific CSS selectors tied to the target site's current markup. If any of these sites changes its layout, the handler's `extract()` will start returning `null` for every article — and today, `null` is treated purely as "discard, nothing worth indexing" (`DefaultDigitalMeStorage.addContent()`). That means a layout change would silently stop indexing all future content from that site, with no record anywhere that anything went wrong.

We want graceful degradation instead: when a handler's expected content is missing, fall back to the pre-handler generic `Jsoup.parse(content).text()` behavior (so content still gets indexed, just noisier) and write a one-time-per-month alert file so the breakage is discoverable.

## The core design problem: `null` is currently overloaded

`extract()` returning `null` is already the deliberate, tested signal for "this page is legitimately not an article" — the front page, section fronts, live-blog hubs. All three handlers were specifically designed around this ("discard the front page" was an explicit requirement of the original visir.is feature). If every `null` were now treated as "layout changed," every non-article page would also start getting indexed via the generic fallback — a regression of that original design goal.

So `DefaultDigitalMeStorage` needs a way to distinguish the two cases when `extract()` returns `null`:
1. **Legitimately not an article** (front page, etc.) → discard, exactly as today.
2. **Should be an article, but nothing was found** → layout changed → fall back + report.

## Design

### `PageHandler` interface — two new methods

```java
boolean looksLikeArticleUrl(String url);
String siteName();
```

`looksLikeArticleUrl(url)` is a cheap, independent heuristic — distinct from `matches(url)` (which only answers "does this handler own this domain"). It's consulted *only* when `extract()` has already returned `null`, to decide which of the two cases above applies. Per-handler implementations, based on each site's real article URL shape (already observed during each handler's original design investigation):

- `VisirPageHandler`: `url.contains("/g/")` — visir's article path segment (e.g. `/g/20262909759d/messi-allt-i-ollu...`).
- `DVPageHandler`: path matches `/\d+/\d{4}/\d{2}/\d{2}/` — dv's `/<category-id>/<year>/<month>/<day>/<slug>` shape.
- `FotboltiPageHandler`: `url.contains("/news/")` — fotbolti's article path segment.

None of these patterns match any of the three sites' front-page URLs already used in existing tests, so the existing "discard the front page" tests are unaffected by this change — verified by inspection, not just assumed.

`siteName()` returns a short, filename-safe slug (`"visir"`, `"dv"`, `"fotbolti"`) used only for the error filename — explicit rather than derived from the class name, so it doesn't depend on a naming convention holding forever.

### New class: `LayoutChangeReporter`

Package `com.breynisson.router.digitalme`, alongside `DefaultDigitalMeStorage`. Constructed with `dataDir`, same pattern as `ResourceReceiver`:

```java
public LayoutChangeReporter(String dataDir)
public void report(String siteName, String message)
```

`report()` resolves `<dataDir>/errors/`, creating it if needed. Before writing, it checks whether a file already exists there matching `<year>-<month>-*-<siteName>.txt` (the current calendar month, that site) — if so, it's a no-op (already reported this month). Otherwise it writes `<year>-<month>-<day>-<hour>-<minute>-<second>-<siteName>.txt` with `message` as the file's entire content, per the exact filename format requested.

No extra synchronization is needed inside this class: its only caller, `DefaultDigitalMeStorage.addContent()`, already holds a `ReentrantLock` around its whole body, so calls are already serialized.

### Wiring into `DefaultDigitalMeStorage.addContent()`

Inside the existing `handler.isPresent()` branch, once `extract()` returns `null`:

```java
} else if (pageHandler.looksLikeArticleUrl(addContentRequest.getSource())) {
    reportLayoutChange(pageHandler, addContentRequest.getSource());
    content = normalize(Jsoup.parse(decoded).text());
} else {
    return discard(contentResponse, "Discarding content with no extractable body", addContentRequest.getSource());
}
```

(`decoded` is the already-JSON-decoded content, computed once per handler-branch invocation and reused for both the handler's own `Jsoup.parse()` call and this fallback one, avoiding a redundant decode.)

A new private helper builds the message and calls the reporter:

```java
private void reportLayoutChange(PageHandler pageHandler, String source) {
    String domain = extractDomain(source); // scheme://host, via java.net.URI
    String message = String.format(
        "%s has changed the layout, so %s can't find the main content. Falling back to default jsoup handling.",
        domain, pageHandler.getClass().getSimpleName());
    layoutChangeReporter.report(pageHandler.siteName(), message);
}
```

This produces exactly the example message given: `"https://www.visir.is has changed the layout, so VisirPageHandler can't find the main content. Falling back to default jsoup handling."` — the domain comes from parsing the actual failing URL (so it's accurate regardless of `www.` or other subdomain variations), and the handler name comes directly from `getClass().getSimpleName()` (no interface method needed for this part — `siteName()` is used only for the filename slug).

`DefaultDigitalMeStorage`'s constructor already receives `dataDir`; it gains one new field, `layoutChangeReporter`, constructed the same way `resourceReceiver` already is.

## Testing

- Each handler's existing test class gets 2 new tests: `looksLikeArticleUrl()` true for a real-shaped article URL, false for the front page; `siteName()` returns the expected slug.
- New `LayoutChangeReporterTest` (using a `@TempDir` as `dataDir`): first `report()` call writes a file with the exact expected filename shape and content; a second `report()` call for the same site within the same month is a no-op (still exactly one file); the file's content equals the message passed in.
- `DefaultDigitalMeStorageTest` gets a small number of new end-to-end cases (the underlying mechanism is shared/generic across handlers, so one representative handler — Visir — is enough at this level; per-handler `looksLikeArticleUrl`/`siteName` correctness is already covered by each handler's own unit tests):
  - An article-shaped visir.is URL whose HTML lacks the expected `articleBody` marker gets indexed via the generic fallback (searchable), and an error file appears under `<dataDir>/errors/` with the expected content.
  - A second such failure for the same site within the same month still gets indexed via fallback, but does not produce a second error file.
  - The existing `addContentDiscardsVisirFrontPage` test is unmodified and must continue to pass unchanged, proving the front-page-discard behavior survived this change.

## Out of scope

- No changes to `PageHandlers` registry itself (`find()` logic is unaffected).
- No changes to the generic Jsoup fallback path used for unmatched (non-handler) URLs — this feature only affects URLs that *do* match a registered handler.
- No retention/cleanup policy for the `errors/` folder — files accumulate indefinitely, same as `mcp-resources/`.
- No alerting/notification beyond writing the file (e.g. no email, no Slack) — out of scope for this change.
