# Design: CNN live-blog content support

## Problem

The final whole-branch review of `CNNPageHandler` found that CNN live-blog pages (e.g. `https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump`) share the standard `/YYYY/MM/DD/` article URL shape but lack `div[itemprop=articleBody]`, so `extract()` always returned `null` for them. That triggered a spurious once-per-month "layout changed" alert every time a live-blog was submitted. The fix at the time excluded `/live-news/` URLs from `looksLikeArticleUrl()`, which stopped the false alert — but as a side effect, it also means live-blog content is now silently and permanently discarded, never indexed. The user has now hit this in practice and wants live-blog content actually captured, not discarded.

## Investigation

Fetched the real live-blog URL directly. Key findings:

- `div[itemprop=articleBody]` — 0 occurrences, confirming why `extract()` returns `null` today.
- `p[data-component-name="paragraph"]` — 107 occurrences, document-wide. Content is spread across many individual `<article data-component-name="live-story-post">` blocks (one per timeline update), each containing its own real paragraph(s) plus a sub-headline (`<h2 class="live-story-post__headline">`) and social-share buttons (SVG icons, no text noise). Inspected one such block directly: its paragraphs are genuine post content with no related-content contamination.
- The real headline is still a single `<h1 id="maincontent">` (same `id` CNN uses on standard articles), just with a different CSS class (`headline_live-story__text` vs. `headline__text`) — irrelevant to the existing tag-only `doc.selectFirst("h1")` lookup, which already works correctly on this page.
- The front page has 0 `p[data-component-name="paragraph"]` occurrences (confirmed by re-checking the sample already fetched for the original design), so this marker remains a safe "nothing worth indexing" signal there even when searched document-wide rather than scoped to a container.

## Design

### `CNNPageHandler.extract()` — two-tier body lookup

```java
@Override
public String extract(Document doc) {
    Element articleBody = doc.selectFirst("div[itemprop=articleBody]");
    Elements paragraphs = articleBody != null
            ? articleBody.select("p[data-component-name=paragraph]")
            : doc.select("p[data-component-name=paragraph]");
    if (paragraphs.isEmpty()) {
        return null;
    }
    Element headline = doc.selectFirst("h1");
    String body = paragraphs.text();
    if (headline == null) {
        return body;
    }
    return headline.text() + "\n\n" + body;
}
```

- If `articleBody` exists (standard article template), paragraph selection stays scoped within it — byte-for-byte the same behavior as today for standard articles, so no regression risk there.
- If `articleBody` is absent, fall back to a document-wide paragraph search — this is what picks up live-blog content, since live-blog paragraphs aren't wrapped in a single container.
- If neither yields any paragraphs, return `null` — the discard signal is unchanged, still correctly covering the front page and any other page with neither template's content markers.

This mirrors `FotboltiPageHandler`'s "empty selection is the discard signal" pattern, combined with the existing "scoped container when available" pattern already used by `DVPageHandler`/`CNNPageHandler`'s standard-article path — not a new mechanism, a combination of two already-established ones.

**Known, accepted limitation** (same category as the standard-article path's existing subheading omission): each live-blog post's own sub-headline (`<h2 class="live-story-post__headline">`) is not captured, only the paragraph body — consistent with not chasing perfect structural completeness, matching the tolerance already accepted for `FotboltiPageHandler` and the standard CNN article path.

### `CNNPageHandler.looksLikeArticleUrl()` — revert the `/live-news/` exclusion

```java
@Override
public boolean looksLikeArticleUrl(String url) {
    return ARTICLE_URL_PATTERN.matcher(url).find();
}
```

Now that `extract()` correctly returns real content for live-blogs under normal conditions, the exclusion that was added to stop false "layout changed" alerts is no longer needed — `extract()` simply won't return `null` for a functioning live-blog anymore, so the alert-triggering branch is never reached for it. Keeping the exclusion would now be actively counterproductive: if CNN's live-blog template itself broke in the future (e.g. renamed `data-component-name="paragraph"`), the exclusion would cause that genuine breakage to be silently discarded instead of triggering the intended alert.

## Testing

- `CNNPageHandlerTest`: new fixture mirroring the live-blog structure (no `articleBody`, multiple `<article data-component-name="live-story-post">` blocks each with their own `p[data-component-name=paragraph]`, plus a real `h1`) asserts `extract()` returns headline + all paragraphs' text, joined. The existing `looksLikeArticleUrlForArticlePath`/`doesNotLookLikeArticleUrlForLiveBlog` tests: the latter is removed since the behavior it tested is being reverted, and a new test confirms live-blog URLs now correctly return `true` from `looksLikeArticleUrl()` again. The existing standard-article and front-page-shaped `extract()` tests are unmodified and must keep passing (proving the standard-article path and discard path are unaffected).
- `DefaultDigitalMeStorageTest`: one new end-to-end case (built via `ObjectMapper.writeValueAsString(html)`, per the established project convention) proving a live-blog-shaped submission gets indexed with content from multiple post blocks. The existing CNN article-extraction and front-page-discard end-to-end tests are unmodified and must keep passing.

## Out of scope

- No changes to `VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`, `PageHandler`, `PageHandlers`, `DefaultDigitalMeStorage`, `LayoutChangeReporter`, or the Chrome extension.
- Live-blog post sub-headlines are not captured (see Known, accepted limitation above).
- Only verified against one live-blog URL, the one standard article, and the front page — same category of assumption made for every handler feature so far.
