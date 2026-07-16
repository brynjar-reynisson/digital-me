# Design: Reddit (reddit.com) page extraction handler

## Problem

`digital-me` has four `PageHandler` implementations (`VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`, `CNNPageHandler`). This adds a fifth, `RedditPageHandler`, for reddit.com. Individual post pages (a post plus its comments) should be treated as articles; the front page and any "group" page showing many post links (subreddit fronts, multireddit fronts, etc.) should be discarded, the same way the front page is discarded for every other handler.

## Investigation

reddit.com actively blocks automated fetches (`curl`, and Anthropic's own `WebFetch` tool) with a JavaScript bot-verification challenge — every programmatic request receives a "Please wait for verification" shell page, never real content. This is specific to reddit.com; none of the previous four sites had this. Since the real production content never comes from a server-side fetch anyway (the Chrome extension captures `document.body.innerHTML` from the user's own authenticated browser session, which is never subject to this challenge), this only affected *investigation*, not the shipped feature. The user captured three real samples directly from their browser via DevTools (`copy(document.body.innerHTML)`): an individual post page, a subreddit listing page (`r/OpenAI`... in practice `r/self`, since the first save was of the wrong tab — confirmed the same structure applies), and the true reddit.com front page.

Key findings:
- Reddit's current frontend ("Shreddit") is built from custom web components (`<shreddit-post>`, `<shreddit-post-text-body>`, `<shreddit-comment-tree>`, etc.), not the semantic-HTML-plus-CSS-classes style of the previous four sites.
- The post body text is marked with an RDFa `property="schema:articleBody"` attribute (the RDFa-style equivalent of the microdata `itemprop="articleBody"` `VisirPageHandler` already relies on for visir.is) on a `<div>` inside `<shreddit-post-text-body>`.
- **Critically, this marker is not unique to individual post pages.** The subreddit listing sample has 27 occurrences of `property="schema:articleBody"` (one per post preview card in the feed) and the front page has 8 — both non-zero. Unlike every previous handler, "does this content marker exist" cannot be the discard signal here, since feed pages render the same marker once per card.
- The actual distinguishing signal is a `view-context` attribute. On the individual post page, the single post's `<shreddit-post>` and `<shreddit-post-text-body>` elements both carry `view-context="CommentsPage"` (confirmed: exactly 3 occurrences, all belonging to the one post being viewed, 0 occurrences of any feed-context value). On the subreddit listing page, every post card instead carries `view-context="SubredditFeed"` (81 occurrences = 3 × 27 cards) and `CommentsPage` is completely absent (0 occurrences). On the front page, cards carry `view-context="AggregateFeed"` and `CommentsPage` is again completely absent (0 occurrences). This was verified across all three real samples, not assumed.
- The real post title is a single `<h1 slot="title" ...>` containing the visible title text. A second, unrelated `<h1>` exists further down the page (`aria-label="Comments Section"`) but is visually hidden (`class="absolute -top-full -start-full w-px h-px overflow-hidden"`) and does not carry `slot="title"` — scoping the headline selector to `h1[slot=title]` avoids any dependency on document order to pick the right one.
- Individual post URLs have a distinctive path shape: `/r/<subreddit>/comments/<id>/<slug>/`. Listing pages (front page, subreddit fronts) don't contain `/comments/`.
- Each comment's text lives in `<div slot="comment" ...>`, nested inside a `<shreddit-comment>` element (which also carries metadata attributes like `depth`, `thingid`, `author` — not used for extraction, just confirming structure). `slot="comment"` appears exactly 28 times in the sample, matching the post's own reported `total-comments="28"` count exactly — a reliable, exclusive marker. `<shreddit-comment>` (and therefore `div[slot=comment]`) does not appear at all on either listing-page sample (0 occurrences on both the subreddit feed and the front page), so no extra scoping is needed to keep comment extraction from ever firing on a feed page — the primary discard gate (`schema:articleBody` scoped under `view-context=CommentsPage`) already ensures comments are only ever selected on a genuine post page.

**Known, accepted scope decision:** comment *nesting/reply structure* (parent-child relationships, depth) is not preserved — all comments (top-level and nested replies alike) are flattened into one joined text blob, in document order. This keeps the selector simple (`shreddit-comment div[slot=comment]`, unscoped by depth) and matches the project's established tolerance for imperfect structural fidelity in exchange for simplicity (e.g. `FotboltiPageHandler` doesn't preserve paragraph-vs-related-content boundaries beyond exclusion either). **Known, accepted limitation:** a link-only or image/video-only post (no text body) won't have a `schema:articleBody` element even on a genuine `/comments/...` page — `extract()` will return `null` for it, and since its URL matches `looksLikeArticleUrl()`, the existing layout-change fallback mechanism will index it via generic Jsoup and (once per month) log a "layout changed" alert for reddit even though nothing is actually broken. This mirrors the same category of edge case already accepted for CNN's video-only articles, and isn't addressed further here.

## Design

### `RedditPageHandler` (new)

Package `com.breynisson.router.extract`, alongside the four existing handlers:

- `matches(url)`: `url.contains("reddit.com")`
- `looksLikeArticleUrl(url)`: `url.contains("/comments/")`
- `siteName()`: `"reddit"`
- `extract(doc)`:
  - Select `shreddit-post-text-body[view-context=CommentsPage] div[property=schema:articleBody]`. If absent, return `null` (discard signal — covers the front page, subreddit fronts, and any other feed-shaped page, since `view-context="CommentsPage"` only ever appears on the single post actually being viewed on a comments page).
  - Select `h1[slot=title]` for the headline.
  - Select `shreddit-comment div[slot=comment]` for the comments, joined via Jsoup's `Elements.text()`. If there are none (e.g. a post with zero comments), this is simply an empty string, not a discard trigger — the post's own body is still returned.
  - Return `headline + "\n\n" + postBody + "\n\n" + comments` (comments appended after the post body; the middle separator is only added when comments are non-empty) — same headline-plus-body join convention as the other four handlers, extended with one more segment.

### `PageHandlers` registry

One line added to the existing `HANDLERS` list: `new RedditPageHandler()`.

### No changes to `DefaultDigitalMeStorage`, `PageHandler`, `LayoutChangeReporter`, or the four existing handlers

The registry wiring, the JSON-decoding fix, and the layout-change fallback/report mechanism all already apply generically to any registered handler.

## Testing

- `RedditPageHandlerTest`: `matches()`/`looksLikeArticleUrl()`/`siteName()` covered the same way as the existing handlers' tests; `extract()` against a local HTML fixture (mirroring the real structure: `h1[slot=title]`, a `shreddit-post-text-body[view-context=CommentsPage]` wrapping `div[property=schema:articleBody]` with paragraph text, plus two or more `shreddit-comment` elements each with their own `div[slot=comment]` text) returns headline + post body + all comments' text, asserting on each comment individually (not just one, per this project's established convention for multi-part-extraction tests); `extract()` against a post fixture with zero comments still returns headline + post body (comments segment is simply absent, not a failure); `extract()` against a feed-shaped fixture (multiple post cards each with `schema:articleBody` but `view-context="SubredditFeed"`, no `CommentsPage` or `shreddit-comment` anywhere) returns `null` — this specific test is the regression guard for the core design risk (that content-marker presence alone would incorrectly treat a feed page as extractable).
- `PageHandlersTest`: add a case confirming `find()` resolves a reddit.com URL to `RedditPageHandler`.
- `DefaultDigitalMeStorageTest`: two end-to-end cases, both built via `ObjectMapper.writeValueAsString(html)` (real Chrome-extension payload shape) — one proving post extraction survives the JSON-escaping round trip and indexes cleanly, one proving a feed-shaped (subreddit/front-page-like) submission is discarded.

## Out of scope

- No changes to `VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`, `CNNPageHandler`, `PageHandler`, `PageHandlers`' existing entries, `DefaultDigitalMeStorage`, `LayoutChangeReporter`, or the Chrome extension.
- Comment nesting/reply structure is not preserved, only flattened text (see Investigation).
- Link-only / image-only / video-only posts without a text body are not specially handled (see Investigation) — they fall through to the existing layout-change fallback mechanism.
- Only verified against one post, one subreddit listing, and the front page — same category of assumption made for every previous handler, though this time backed by real DOM samples from an actual authenticated browser session rather than a `curl` fetch (which reddit.com blocks entirely).
