# Design: fotbolti.net page extraction handler

## Problem

`digital-me` has two `PageHandler` implementations already (`VisirPageHandler`, `DVPageHandler`). This adds a third, `FotboltiPageHandler`, for fotbolti.net (an Icelandic football news site), further validating the registry's reusability across sites with unrelated markup and CMS platforms.

## Investigation

Fetched a real fotbolti.net article (`https://fotbolti.net/news/16-07-2026/otrulegir-yfirburdur-argentinu-eftir-ad-england-komst-yfir`), a second article for pattern comparison, and the front page. fotbolti.net is built on Astro with Tailwind CSS — markup is minified to a single line and class names are mostly auto-generated Tailwind utility strings (including some containing `(`, `[`, `&`, `:` characters that are unsafe for Jsoup's plain `.class` selector shorthand), not semantic CSS classes.

Key findings:
- The article's `<h1>` appears exactly once on article pages, zero times on the front page — same shape as the previous two handlers.
- The article body is **split into two separate `<div>` elements** by the CMS: an intro paragraph (or two), then an inline "TENGT EFNI" (Related Content) card linking to a different article (rendered as an `<a>` wrapping an `<h3>` with that other article's headline), then a second `<div>` — carrying an additional `article-html` class not present on the first — containing the rest of the body. This split pattern was consistent across both sampled articles, so it appears to be a deliberate, standard template feature (likely how the CMS injects a promoted/related link mid-article), not an anomaly.
- Both body divs — despite the split — share the **exact same three plain Tailwind classes**: `font-body`, `text-base`, `leading-8`. A search across the full article page found only these two elements matching that combination; the front page has zero matches. The "TENGT EFNI" card itself doesn't match (it's an `<a>`/`<h3>` pair, not a `<div>` with these classes), and the ad-slot placeholder divs between them don't either.
- This means a single selector, `div.font-body.text-base.leading-8`, cleanly captures *both* body divs in one pass — via Jsoup's `Elements.text()` (already used the same way by `DvPageHandler`'s `Elements` selection), it joins both divs' text while structurally excluding the related-card's unrelated headline and the empty ad slots in between. No sibling-order or position-based logic is needed.
- Because the selector naturally returns zero elements on the front page (and, by the same reasoning, any other non-article page that doesn't render this two-part body structure), "is the selection empty" doubles as the discard signal — there's no need for a distinct existence check like `VisirPageHandler`'s `articleBody != null` or `DvPageHandler`'s `article-body != null`; here the extraction selector and the discard check are the same query.

## Design

### `FotboltiPageHandler` (new)

Package `com.breynisson.router.extract`, alongside `VisirPageHandler` and `DVPageHandler`:

- `matches(url)`: `url.contains("fotbolti.net")`
- `extract(doc)`:
  - Select `div.font-body.text-base.leading-8`. If the result is empty, return `null` (discard — covers the front page and, per the investigation, any other non-article page that doesn't render this body structure).
  - Select `h1` for the headline (present once on article pages, absent on the front page).
  - Join the selected divs' text via `Elements.text()`.
  - Return `headline + "\n\n" + body` — identical format to the other two handlers.

### `PageHandlers` registry

One line added to the existing `HANDLERS` list: `new FotboltiPageHandler()`.

### No changes to `DefaultDigitalMeStorage`, `PageHandler`, `VisirPageHandler`, or `DVPageHandler`

The registry-based wiring and the JSON-decoding fix both already apply generically to any registered handler.

## Testing

- `FotboltiPageHandlerTest`: `matches()` true/false for fotbolti.net vs. unrelated URLs; `extract()` against a local HTML fixture mirroring the real split-body structure (intro paragraph div, an ad-slot placeholder div, an inline "TENGT EFNI" related-article card, a second body div with the extra marker class) returns headline + **both** paragraphs' text, excluding the related card's headline text; `extract()` against a front-page-shaped fixture (no matching divs, no `h1`) returns `null`.
- `PageHandlersTest`: add a case confirming `find()` resolves a fotbolti.net URL to `FotboltiPageHandler`.
- `DefaultDigitalMeStorageTest`: two end-to-end cases, both built via `ObjectMapper.writeValueAsString(html)` (reproducing the real Chrome-extension payload shape, per the established project convention) — one proving article extraction survives the JSON-escaping round trip and indexes both body parts, one proving a front-page-shaped submission is discarded.

## Out of scope

- No changes to `VisirPageHandler`, `DVPageHandler`, `PageHandler`, `PageHandlers`' existing entries, `DefaultDigitalMeStorage`, or the Chrome extension.
- No other new site handlers in this change.
- Only verified against two sampled articles and the front page — if a fotbolti.net page type not sampled here (e.g. a live-match ticker, a photo gallery, a video page) renders the same three-class combination without being a genuine article, it would be incorrectly extracted rather than discarded. Not addressed here; same category of assumption made for the previous two handlers.
