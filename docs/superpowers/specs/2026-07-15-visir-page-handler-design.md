# Design: per-site page extraction handlers, starting with visir.is

## Problem

`/addContent` currently strips HTML from web submissions with a single generic rule: `Jsoup.parse(content).text()`. For most sites this is fine, but for content-heavy news sites like visir.is it pulls in the full page chrome — nav menus, "most read" lists, related-article links, footer — alongside the actual article text. A sample article page (`.../g/20262909759d/messi-allt-i-ollu-thegar-argentina-for-i-ur-slita-leikinn`) produces a wall of text where the actual article body is a small fraction of the content.

We also currently index the visir.is front page (`https://www.visir.is`) as-is, which contains nothing but navigation and article teasers — no standalone value once individual articles are indexed separately.

We need a way to plug in site-specific extraction logic, generically, so future sites with the same problem can get their own handler without changing the core `/addContent` flow.

## Investigation

Fetched a live visir.is article page and its front page to inspect actual markup (not just the already-flattened text we'd previously indexed, which had lost all DOM structure):

- Every visir.is article page has exactly one `<div itemprop="articleBody">` containing the article's `<p>` paragraphs, cleanly separated from nav/related-content markup.
- The article headline is a plain `<h1>` in the same `article.article-single` container, but outside `article-single__content`.
- The front page (`https://www.visir.is`) has **zero** `itemprop="articleBody"` elements — confirmed by fetching it directly.

This means "discard if no articleBody element is found" is sufficient to cover both the front page and (presumably) other non-article visir.is pages (section fronts, live-blog hubs), without a separate hardcoded root-URL check.

## Design

### `PageHandler` interface (new)

Package `com.breynisson.router.extract` (alongside the existing `YouTubeCaptionExtractor`, which is a similarly-shaped single-purpose extractor):

```java
public interface PageHandler {
    boolean matches(String url);
    String extract(Document doc);  // null return means: discard, nothing worth indexing
}
```

`extract` takes an already-parsed Jsoup `Document` (not a raw string) so implementations can use CSS selectors freely.

### `VisirPageHandler` (new)

- `matches(url)`: `url.contains("visir.is")`
- `extract(doc)`:
  - Select `div[itemprop=articleBody]`. If none found, return `null` (discard).
  - Otherwise return the page's `h1` text (if present) as a leading line, followed by the articleBody element's text — giving headline + clean paragraph text with no nav/related-content noise.

### `PageHandlers` registry (new)

Static holder for all registered handlers:

```java
public final class PageHandlers {
    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler());
    public static Optional<PageHandler> find(String url) { ... }
}
```

Adding a future site's handler is: one new `PageHandler` implementation + one line in `HANDLERS`. No other code changes needed.

### Integration into `DefaultDigitalMeStorage.addContent`

Inside the existing `if (addContentRequest.getSource().startsWith("http"))` branch, after the existing `ScreenshotCoverage.isCovered()` check and before the YouTube / generic-Jsoup branches:

```java
Optional<PageHandler> handler = PageHandlers.find(addContentRequest.getSource());
if (handler.isPresent()) {
    String extracted = handler.get().extract(Jsoup.parse(content));
    if (extracted == null) {
        log.info("Discarding content with no extractable body: {}", addContentRequest.getSource());
        contentResponse.setSuccess(true);
        return contentResponse;
    }
    content = normalize(extracted);
} else if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
} else {
    content = normalize(Jsoup.parse(content).text());
}
```

`normalize(String)` is a new private helper extracted from the existing inline cleanup (replacing literal `\n`/`\t`/`\r` sequences — an artifact of the Chrome extension's `JSON.stringify` payload — and collapsing whitespace). It's currently duplicated inline in the generic branch; pulling it out means both the generic path and the new handler path share it, since the cleanup isn't Jsoup-specific.

Discard behavior mirrors the existing `ScreenshotCoverage` discard: `contentResponse.setSuccess(true)`, nothing written, nothing indexed.

### Why not a declarative selector-config instead of per-site classes?

Considered a config-driven approach (URL pattern → single CSS selector, generic extractor applies it). Rejected because visir.is already needs two selectors combined (headline + body) with a discard fallback — a single-selector config can't express that without extending the format later. A plain Java class per site, matching the existing `YouTubeCaptionExtractor` pattern in this codebase, stays flexible for whatever the next site needs (multiple selectors, stripping specific sub-elements, custom discard logic) without inventing a mini-DSL.

## Testing

- `VisirPageHandlerTest`: `matches()` true/false for visir.is vs. unrelated URLs; `extract()` against a local HTML fixture (trimmed from the real fetched article) returns headline + body text; `extract()` against a front-page-shaped fixture (no articleBody) returns `null`
- `PageHandlersTest`: `find()` resolves to `VisirPageHandler` for a visir.is URL, empty `Optional` for an unrelated URL
- `DefaultDigitalMeStorageTest`: add a case mirroring the existing `addContentDiscardsCoveredScreenshotUrl` test, for a visir.is submission shaped like the front page (discarded, nothing indexed); add a case verifying a visir.is article submission gets the clean extracted text indexed rather than raw nav/related-content text

## Docs

Update `docs/architecture.md`:
- `/addContent` description: note that HTTP sources are checked against the `PageHandlers` registry before falling back to generic Jsoup stripping, and that a handler match with no extractable content is silently discarded (same as the `ScreenshotCoverage` case)
- Add a `VisirPageHandler` / `PageHandlers` subsystem note, following the existing style of the `ScreenshotCoverage` note

## Out of scope

- No other site handlers are being added in this change — the registry starts with just visir.is
- No change to how content reaches `/addContent` (Chrome extension payload format, MCP resource writing, embedding indexing) — only the extraction step inside the existing HTTP branch changes
