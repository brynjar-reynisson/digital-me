# Design: CNN (edition.cnn.com) page extraction handler

## Problem

`digital-me` has three `PageHandler` implementations (`VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`). This adds a fourth, `CNNPageHandler`, for edition.cnn.com — the first non-Icelandic, English-language site handled this way, and the first handler built since the `looksLikeArticleUrl()`/`siteName()` interface additions from the layout-error-handling feature, so it must implement those from the start.

## Investigation

Fetched a real CNN article (`https://edition.cnn.com/2026/07/15/science/new-jersey-fireball-rare-meteorite`) and the front page. CNN's markup is large (4-5 MB per page, heavy with inline component templates) but structurally clean:

- The article page has exactly one `<div itemprop="articleBody">` — the same schema.org marker `VisirPageHandler` already relies on for visir.is. The front page has zero occurrences.
- The real headline is a single `<h1 data-editable="headlineText" ...>`. A raw byte-level search of the HTML also turns up `<h1` text embedded inside `<script>` blocks — these are Handlebars-style client-side template strings (literal JavaScript, e.g. `class="'+s(typeof(...`), not real markup. Jsoup's HTML parser treats `<script>` contents as raw text, not nested elements, so `doc.selectFirst("h1")` will not be fooled by these — confirmed by checking that the front page's only apparent `<h1>` match is one of these fake script-embedded strings, meaning it has zero real `<h1>` elements once actually parsed.
- Inside `articleBody`, real paragraphs are tagged `data-component-name="paragraph"`. Embedded "related article" teaser cards (their own headline text, image caption, "N min read" metadata) are interspersed between paragraphs but are structurally distinct — their own descriptive `<p>` (`class="vossi-related-content_elevate__headline"`) does not carry `data-component-name="paragraph"`. Scoping paragraph selection to that attribute, within `articleBody`, cleanly excludes this noise — the same technique `DVPageHandler` already uses (there, direct-child `<p>` scoping; here, an attribute filter, since CNN's related-content blocks aren't reliably distinguishable by DOM nesting depth alone).
- CNN article URLs follow a clean `/YYYY/MM/DD/<section>/<slug>` shape (e.g. `/2026/07/15/science/new-jersey-fireball-rare-meteorite`), giving a straightforward `looksLikeArticleUrl()` heuristic.

**Known, accepted limitation** (same category as an already-accepted `FotboltiPageHandler` tradeoff): CNN articles can include subheadings tagged `data-component-name="subheader"`, which this design does not capture — only `data-component-name="paragraph"` elements are extracted. This is a content-completeness limitation, not a correctness bug (nothing wrong gets indexed, some structural content is just omitted), consistent with the tolerance already accepted for fotbolti.net's `<p>`-only extraction.

## Design

### `CNNPageHandler` (new)

Package `com.breynisson.router.extract`, alongside the existing three handlers, implementing the full `PageHandler` interface (as it stands after the layout-error-handling feature: `matches`, `extract`, `looksLikeArticleUrl`, `siteName`):

- `matches(url)`: `url.contains("cnn.com")`
- `looksLikeArticleUrl(url)`: path matches the regex `/\d{4}/\d{2}/\d{2}/`
- `siteName()`: `"cnn"`
- `extract(doc)`:
  - Select `div[itemprop=articleBody]`. If absent, return `null` (discard signal, or — per the already-shipped layout-error-handling mechanism — a "layout changed, fall back + report" signal if the URL looks like an article).
  - Select `h1` for the headline.
  - Within the found `articleBody` element, select `p[data-component-name=paragraph]`, joined via Jsoup's `Elements.text()`.
  - Return `headline + "\n\n" + body` — identical format to the other three handlers.

### `PageHandlers` registry

One line added to the existing `HANDLERS` list: `new CNNPageHandler()`.

### No changes to `DefaultDigitalMeStorage`, `PageHandler`, `LayoutChangeReporter`, or the other three handlers

The registry wiring, the JSON-decoding fix, and the layout-change fallback/report mechanism all already apply generically to any registered handler — this is the first handler added since that mechanism shipped, so it directly validates that "add a new site = one class + one registry line, no other code changes" still holds even after layout-error-handling's interface changes.

## Testing

- `CNNPageHandlerTest`: `matches()`/`looksLikeArticleUrl()`/`siteName()` covered the same way as the other three handlers' tests; `extract()` against a local HTML fixture (mirroring the real structure: `h1`, `div[itemprop=articleBody]` containing `p[data-component-name=paragraph]` elements interspersed with a related-content block) returns headline + paragraph text, excluding the related-content card's own headline/caption text; `extract()` against a front-page-shaped fixture (no `articleBody`, no real `h1`) returns `null`.
- `PageHandlersTest`: add a case confirming `find()` resolves an edition.cnn.com URL to `CNNPageHandler`.
- `DefaultDigitalMeStorageTest`: two end-to-end cases, both built via `ObjectMapper.writeValueAsString(html)` (real Chrome-extension payload shape) — one proving article extraction survives the JSON-escaping round trip and indexes cleanly, one proving a front-page-shaped submission is discarded.

## Out of scope

- No changes to the other three handlers, `PageHandler`, `PageHandlers`' existing entries, `DefaultDigitalMeStorage`, `LayoutChangeReporter`, or the Chrome extension.
- No other new site handlers in this change.
- Subheadings (`data-component-name="subheader"`) are not extracted — accepted limitation, see Investigation.
- Only verified against one article and the front page — same category of assumption made for all three prior handlers.
