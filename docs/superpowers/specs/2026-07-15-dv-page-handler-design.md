# Design: dv.is page extraction handler

## Problem

`digital-me` already has a pluggable per-site extraction mechanism (`PageHandler` interface + `PageHandlers` registry), introduced for visir.is. dv.is is another Icelandic news site whose article pages, when stripped via the generic `Jsoup.parse(content).text()` fallback, would pull in nav/sidebar/related-content noise the same way visir.is did. This adds a second handler, `DvPageHandler`, and validates that the registry design (from the visir.is feature) is genuinely reusable: adding a new site should require one new class plus one line in `PageHandlers`, nothing else.

## Investigation

Fetched a real dv.is article (`https://www.dv.is/433/2026/07/15/storstjarna-faer-a-baukinn-djammadi-med-ahrifavaldi-a-snekkju-eftir-ad-hafa-valdid`) and the dv.is front page to inspect actual markup. dv.is runs Drupal (visible from its `field--name-*` CSS class conventions), a different CMS from visir.is.

Key findings:
- The article page has exactly one `<div class="article-body photoswipe-gallery">` wrapper around the article's text content. This class is **absent from the front page** (0 occurrences) — same "discard if absent" signal shape as visir's `itemprop=articleBody`.
- Unlike visir.is, dv.is's inner text-field class (`field--name-body`) is **not unique to articles** — Drupal reuses it generically for footer blocks (site address, copyright notice) and sidebar/promo widgets (a "Tarot Spil á DV" box, a "tip us" box). The front page has 4 occurrences of `field--name-body`, none of them the article body. A handler that searched the whole document for `div.field--name-body` (rather than scoping it under `div.article-body`) would incorrectly match the first sidebar widget on non-article pages instead of discarding.
- The article's `<h1>` appears exactly once on the article page and zero times on the front page (same as visir).
- Inside the article body, embedded images appear as sibling `<article class="media ...">` blocks interspersed between `<p>` paragraphs, each carrying a visually-hidden `"Mynd"` (Icelandic: "Image") accessibility label. Selecting only direct-child `<p>` elements of the body field (rather than the whole body div's `.text()`) avoids pulling that label text into the extracted content.
- The related-articles section ("Fleiri fréttir") sits outside `div.article-body`, after it closes — naturally excluded by scoping to `article-body`, same as visir's related-content sections were excluded by scoping to `articleBody`.
- `class="article-body ..."` is itself an HTML attribute, subject to the same JSON-escaping the visir.is feature discovered and fixed centrally in `DefaultDigitalMeStorage.decodeIfJsonEncoded()`. That fix already runs before any `PageHandler.extract()` call, so `DvPageHandler` needs no decoding logic of its own — but its own test suite must include an end-to-end case built from a JSON-escaped fixture (via `ObjectMapper.writeValueAsString(html)`), not just clean HTML, so a future regression in either handler's attribute-based selectors would actually be caught.

## Design

### `DvPageHandler` (new)

Package `com.breynisson.router.extract`, alongside `VisirPageHandler`:

- `matches(url)`: `url.contains("dv.is")`
- `extract(doc)`:
  - Select `div.article-body`. If absent, return `null` (discard — covers the front page and, per the investigation, any other non-article page using the same reasoning validated for visir.is).
  - Select `h1` for the headline (present once on article pages, absent on the front page).
  - Select `div.article-body .field--name-body > p` (direct-child paragraphs only, excluding the embedded `<article class="media...">` image blocks) and join via Jsoup's `Elements.text()`.
  - Return `headline + "\n\n" + paragraphs` — identical format to `VisirPageHandler`, for consistency.

### `PageHandlers` registry

One line added to the existing `HANDLERS` list: `new DvPageHandler()`. No other code changes — this validates the extensibility promise made in the visir.is design.

### No changes to `DefaultDigitalMeStorage`, `PageHandler`, or `VisirPageHandler`

The registry-based wiring and the JSON-decoding fix are both already in place and apply generically to any registered handler.

## Testing

- `DvPageHandlerTest`: `matches()` true/false for dv.is vs. unrelated URLs; `extract()` against a local HTML fixture (mirroring the real article's structure: `div.article-body` wrapping `h1` + `field--name-body` with interspersed `<article class="media...">` image blocks) returns headline + paragraph text, excluding both the "Mynd" label noise and any related-content section; `extract()` against a front-page-shaped fixture (widgets using `field--name-body` outside any `article-body` wrapper, no `h1`) returns `null`.
- `PageHandlersTest`: add a case confirming `find()` resolves a dv.is URL to `DvPageHandler`.
- `DefaultDigitalMeStorageTest`: two end-to-end cases, both built via `ObjectMapper.writeValueAsString(html)` (reproducing the real Chrome-extension payload shape) rather than clean HTML — one proving article extraction survives the JSON-escaping round trip and indexes cleanly, one proving a front-page-shaped submission is discarded.

## Out of scope

- No changes to `VisirPageHandler`, `PageHandler`, `PageHandlers`' existing entries, `DefaultDigitalMeStorage`, or the Chrome extension.
- No other new site handlers in this change.
