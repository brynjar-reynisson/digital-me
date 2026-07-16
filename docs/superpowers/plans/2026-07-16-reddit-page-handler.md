# Reddit Page Extraction Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fifth `PageHandler`, `RedditPageHandler`, that extracts a post's title, body text, and all comments from reddit.com post pages, discarding the front page and any subreddit/multireddit listing page the same way the other four handlers discard non-article pages.

**Architecture:** `RedditPageHandler implements PageHandler` (package `com.breynisson.router.extract`, alongside the four existing handlers): matches any `reddit.com` URL. Unlike every prior handler, the post-body content marker (`div[property="schema:articleBody"]`) is *not* unique to individual post pages — Reddit's feed pages render the same marker once per post-preview card — so the discard signal instead scopes that selector under `shreddit-post-text-body[view-context=CommentsPage]`, an attribute that's exclusive to the single post actually being viewed (confirmed absent on both real listing-page samples gathered during design). When present, extraction returns `h1[slot=title]` (headline) + the post body + all `shreddit-comment div[slot=comment]` elements' joined text (comments), in that order.

**Tech Stack:** Java 19, Jsoup (already a dependency), JUnit 5, AssertJ, Jackson `ObjectMapper` (existing project stack — no new dependencies).

## Global Constraints

- Domain matching uses simple substring containment (`url.contains("reddit.com")`), matching the existing convention.
- `looksLikeArticleUrl(url)` must match `url.contains("/comments/")` (Reddit's individual-post path shape).
- `siteName()` must return `"reddit"`.
- The discard/extraction gate is `shreddit-post-text-body[view-context=CommentsPage] div[property="schema:articleBody"]` — quote the `property` attribute's value in the Jsoup selector string (`div[property="schema:articleBody"]`), since the value contains a colon, which is not a safe unquoted CSS attribute-value character.
- Headline selection must be `h1[slot=title]`, not a bare `h1` — the page also contains a second, visually-hidden `<h1 aria-label="Comments Section">` used for accessibility, which does not carry `slot="title"`.
- Comment selection (`shreddit-comment div[slot=comment]`) is unscoped by nesting depth — all comments (top-level and replies) are flattened into one joined text blob in document order. Comment *count* being zero is not a discard trigger; only the post-body selector being absent triggers `null`.
- No other site handlers are added or modified in this change — only `RedditPageHandler` is added, and the only change to `PageHandlers` is the one new list entry.
- No changes to the Chrome extension, `DefaultDigitalMeStorage`, `PageHandler`, `LayoutChangeReporter`, or the four existing handlers.
- Any test that exercises the full `DefaultDigitalMeStorage.addContent()` pipeline (not just `RedditPageHandler.extract()` in isolation) must build its HTML fixture via `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html)`, reproducing the real Chrome-extension JSON-escaped payload shape — not clean HTML — per the established project convention. Unit tests of `RedditPageHandler.extract()` in isolation use clean HTML fixtures as normal.
- Any test asserting multiple distinct pieces of extracted content (post body + multiple comments) must assert on each distinct piece individually, not just one representative piece — a prior feature's task review caught a test that only proved half of a multi-part extraction actually worked.
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `RedditPageHandler`

**Files:**
- Create: `src/main/java/com/breynisson/router/extract/RedditPageHandler.java`
- Test: `src/test/java/com/breynisson/router/extract/RedditPageHandlerTest.java`

**Interfaces:**
- Produces: `RedditPageHandler implements PageHandler` (interface has 4 methods: `matches`, `extract`, `looksLikeArticleUrl`, `siteName` — all implemented here, since this is a brand-new class). Used by Task 2's `PageHandlers` registry.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/extract/RedditPageHandlerTest.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedditPageHandlerTest {

    private final RedditPageHandler handler = new RedditPageHandler();

    @Test
    void matchesRedditUrl() {
        assertThat(handler.matches("https://www.reddit.com/r/OpenAI/comments/1uxfs3r/system_architects_are_about_to_become_one_of_the/")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void extractsHeadlinePostBodyAndCommentsFromPostPage() {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <h1 slot="title">Should I confront my roommate about this?</h1>
                <shreddit-post-text-body slot="text-body" view-context="CommentsPage">
                  <div property="schema:articleBody">
                    <p>My roommate has been leaving dishes in the sink for two weeks straight and I am at my wit's end.</p>
                  </div>
                </shreddit-post-text-body>
                <shreddit-comment thingid="t1_abc123" depth="0">
                  <div slot="comment">Just talk to them directly, passive aggressive notes never work.</div>
                </shreddit-comment>
                <shreddit-comment thingid="t1_def456" depth="1">
                  <div slot="comment">Agreed, communication is key in any living situation.</div>
                </shreddit-comment>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Should I confront my roommate about this?");
        assertThat(result).contains("leaving dishes in the sink for two weeks straight");
        assertThat(result).contains("Just talk to them directly, passive aggressive notes never work.");
        assertThat(result).contains("Agreed, communication is key in any living situation.");
    }

    @Test
    void extractsPostWithNoComments() {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <h1 slot="title">A quiet post with no replies yet</h1>
                <shreddit-post-text-body slot="text-body" view-context="CommentsPage">
                  <div property="schema:articleBody">
                    <p>Just sharing a thought with nobody to respond yet.</p>
                  </div>
                </shreddit-post-text-body>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("A quiet post with no replies yet");
        assertThat(result).contains("Just sharing a thought with nobody to respond yet.");
    }

    @Test
    void returnsNullForFeedShapedPage() {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a post about relocating to a new city for work.</div>
                </shreddit-post>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a different post about a weekend trip.</div>
                </shreddit-post>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }

    @Test
    void looksLikeArticleUrlForPostPath() {
        assertThat(handler.looksLikeArticleUrl("https://www.reddit.com/r/OpenAI/comments/1uxfs3r/system_architects_are_about_to_become_one_of_the/")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.reddit.com/")).isFalse();
    }

    @Test
    void siteNameIsReddit() {
        assertThat(handler.siteName()).isEqualTo("reddit");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=RedditPageHandlerTest`
Expected: compile failure — `RedditPageHandler` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/extract/RedditPageHandler.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class RedditPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("reddit.com");
    }

    @Override
    public String extract(Document doc) {
        Element postBody = doc.selectFirst("shreddit-post-text-body[view-context=CommentsPage] div[property=\"schema:articleBody\"]");
        if (postBody == null) {
            return null;
        }
        Element headline = doc.selectFirst("h1[slot=title]");
        String comments = doc.select("shreddit-comment div[slot=comment]").text();
        String body = comments.isEmpty() ? postBody.text() : postBody.text() + "\n\n" + comments;
        if (headline == null) {
            return body;
        }
        return headline.text() + "\n\n" + body;
    }

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return url.contains("/comments/");
    }

    @Override
    public String siteName() {
        return "reddit";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=RedditPageHandlerTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/RedditPageHandler.java src/test/java/com/breynisson/router/extract/RedditPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add RedditPageHandler to extract post title, body, and comments"
```

---

### Task 2: Register `RedditPageHandler` in `PageHandlers`

**Files:**
- Modify: `src/main/java/com/breynisson/router/extract/PageHandlers.java`
- Modify: `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`

**Interfaces:**
- Consumes: `RedditPageHandler` (Task 1).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/breynisson/router/extract/PageHandlersTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void findsRedditHandlerForRedditUrl() {
        assertThat(PageHandlers.find("https://www.reddit.com/r/OpenAI/comments/1uxfs3r/system_architects_are_about_to_become_one_of_the/"))
                .containsInstanceOf(RedditPageHandler.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `findsRedditHandlerForRedditUrl` FAILS — today's `PageHandlers.HANDLERS` only contains `VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`, `CNNPageHandler`, so `find()` returns empty for a reddit.com URL.

- [ ] **Step 3: Register the handler**

In `src/main/java/com/breynisson/router/extract/PageHandlers.java`, the current content reads:

```java
package com.breynisson.router.extract;

import java.util.List;
import java.util.Optional;

public final class PageHandlers {

    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DVPageHandler(), new FotboltiPageHandler(), new CNNPageHandler());

    private PageHandlers() {
    }

    public static Optional<PageHandler> find(String url) {
        return HANDLERS.stream().filter(handler -> handler.matches(url)).findFirst();
    }
}
```

Change the `HANDLERS` line to:

```java
    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DVPageHandler(), new FotboltiPageHandler(), new CNNPageHandler(), new RedditPageHandler());
```

No other changes to this file.

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0` (the five pre-existing tests plus the new one).

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandlers.java src/test/java/com/breynisson/router/extract/PageHandlersTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: register RedditPageHandler in PageHandlers"
```

---

### Task 3: End-to-end verification through `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `PageHandlers.find()` resolving to `RedditPageHandler` (Task 2); no production code changes — `DefaultDigitalMeStorage` already calls the registry generically.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the file's existing `request(...)`/`cleanupDb(...)` helpers). Both fixtures are built via `ObjectMapper.writeValueAsString(html)` to reproduce the real Chrome-extension payload shape, per this plan's Global Constraints. The first asserts on three distinct search terms — one from the post body, one from each of the two comments — per this plan's "assert on each distinct piece" constraint:

```java
    @Test
    void addContentExtractsRedditPostAndCommentsFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <h1 slot="title">Should I confront my roommate about this?</h1>
                <shreddit-post-text-body slot="text-body" view-context="CommentsPage">
                  <div property="schema:articleBody">
                    <p>My roommate has been leaving dishes in the sink for two weeks straight and I am at my wit's end.</p>
                  </div>
                </shreddit-post-text-body>
                <shreddit-comment thingid="t1_abc123" depth="0">
                  <div slot="comment">Just talk to them directly, passive aggressive notes never work.</div>
                </shreddit-comment>
                <shreddit-comment thingid="t1_def456" depth="1">
                  <div slot="comment">Agreed, communication is key in any living situation.</div>
                </shreddit-comment>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.reddit.com/r/relationships/comments/1abcxyz/should_i_confront_my_roommate_about_this/", "Should I confront my roommate", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("dishes").results().size());
        assertEquals(1, storage.search("passive").results().size());
        assertEquals(1, storage.search("communication").results().size());
        assertFalse(TextEntryDao.findByName("https://www.reddit.com/r/relationships/comments/1abcxyz/should_i_confront_my_roommate_about_this/").isEmpty());

        cleanupDb("https://www.reddit.com/r/relationships/comments/1abcxyz/should_i_confront_my_roommate_about_this/");
    }

    @Test
    void addContentDiscardsRedditFeedPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://www.reddit.com/r/relationships/");
        storage.addContent(request("http://unrelated-reddit-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a post about relocating to a new city for work.</div>
                </shreddit-post>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a different post about a weekend trip.</div>
                </shreddit-post>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.reddit.com/r/relationships/", "Relationships Subreddit", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://www.reddit.com/r/relationships/").isEmpty());
        assertTrue(storage.search("relocating").results().isEmpty());

        cleanupDb("http://unrelated-reddit-seed.com");
    }
```

Note: `https://www.reddit.com/r/relationships/` does not contain `/comments/`, so `looksLikeArticleUrl()` returns `false` for it — this discard test exercises the genuine "not a post page" discard path, not the layout-change fallback path.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: both new tests should already PASS at this point, since Tasks 1-2 already wired `RedditPageHandler` into the registry and `DefaultDigitalMeStorage` already consumes the registry generically (no production code left to change). If either test fails, that's a signal Task 1 or Task 2's implementation has a bug — investigate before proceeding rather than treating this as an expected RED step.

- [ ] **Step 3: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "test: verify RedditPageHandler end-to-end through addContent"
```

---

### Task 4: Document `RedditPageHandler`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `PageHandler` subsystem section**

In `docs/architecture.md`, the section currently reads (heading plus the last three bullets before "To add a new site"):

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler` / `FotboltiPageHandler` / `CNNPageHandler`
...
- `CNNPageHandler` — matches any `cnn.com` URL; ... same category as `FotboltiPageHandler`'s `<p>`-only extraction
- `looksLikeArticleUrl(url)` — cheap per-handler URL-shape heuristic (e.g. `VisirPageHandler` checks for `/g/`, `DVPageHandler` for a `/<id>/<yyyy>/<mm>/<dd>/` path, `FotboltiPageHandler` for `/news/`, `CNNPageHandler` for a `/<yyyy>/<mm>/<dd>/` path), consulted only when `extract()` returns `null`, to distinguish "legitimately not an article" (discard, unchanged) from "should be an article but the layout changed" (fall back + report)
- `siteName()` — short filename-safe slug (`visir`/`dv`/`fotbolti`/`cnn`) used by `LayoutChangeReporter`
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

Rename the heading, add a `RedditPageHandler` bullet right after the `CNNPageHandler` bullet, and update both the `looksLikeArticleUrl(url)` and `siteName()` bullets to mention Reddit — so it reads:

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler` / `FotboltiPageHandler` / `CNNPageHandler` / `RedditPageHandler`
...
- `CNNPageHandler` — matches any `cnn.com` URL; ... same category as `FotboltiPageHandler`'s `<p>`-only extraction
- `RedditPageHandler` — matches any `reddit.com` URL; extracts the `h1[slot=title]` headline plus the post body (`div[property="schema:articleBody"]`) plus all comments (`shreddit-comment div[slot=comment]`, flattened without preserving reply nesting). Unlike every other handler, the post-body marker alone isn't a reliable discard signal — Reddit's feed pages (front page, subreddit/multireddit listings) render the same marker once per post-preview card — so the marker is scoped under `shreddit-post-text-body[view-context=CommentsPage]`, an attribute exclusive to the single post actually being viewed. Returns `null` when that scoped selector finds nothing, covering the front page and any listing page
- `looksLikeArticleUrl(url)` — cheap per-handler URL-shape heuristic (e.g. `VisirPageHandler` checks for `/g/`, `DVPageHandler` for a `/<id>/<yyyy>/<mm>/<dd>/` path, `FotboltiPageHandler` for `/news/`, `CNNPageHandler` for a `/<yyyy>/<mm>/<dd>/` path, `RedditPageHandler` for `/comments/`), consulted only when `extract()` returns `null`, to distinguish "legitimately not an article" (discard, unchanged) from "should be an article but the layout changed" (fall back + report)
- `siteName()` — short filename-safe slug (`visir`/`dv`/`fotbolti`/`cnn`/`reddit`) used by `LayoutChangeReporter`
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

- [ ] **Step 2: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document RedditPageHandler"
```

---

### Task 5: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no new violations (the project has 9 pre-existing violations in files this branch doesn't touch — confirm via `git diff main...feature/reddit_handler --stat` on those exact paths showing an empty diff).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/reddit_handler --stat`
Expected: shows changes to `src/main/java/com/breynisson/router/extract/RedditPageHandler.java` (new), `src/main/java/com/breynisson/router/extract/PageHandlers.java`, `src/test/java/com/breynisson/router/extract/RedditPageHandlerTest.java` (new), `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`, `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST the real reddit.com post HTML (the user has already saved a real sample via DevTools `copy(document.body.innerHTML)` during design — reuse it, or fetch a fresh one the same way, since reddit.com blocks server-side fetches) to `/addContent` directly and confirm via `/search?keywords=...` that a search for a word from the post body and a word from a comment both find it. Then POST a subreddit listing or front-page HTML sample and confirm nothing gets indexed.
