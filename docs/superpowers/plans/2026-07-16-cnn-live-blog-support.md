# CNN Live-Blog Content Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `CNNPageHandler` currently discards CNN live-blog pages entirely (a stopgap fix to stop a false "layout changed" alert). This plan makes it actually extract live-blog content instead, and reverts the stopgap now that the root cause is fixed.

**Architecture:** `CNNPageHandler.extract()` becomes a two-tier lookup: if `div[itemprop=articleBody]` exists (standard article template), paragraph selection stays scoped within it exactly as today; if absent, the same `p[data-component-name=paragraph]` selector is searched document-wide instead, which is what picks up live-blog content (spread across many separate `live-story-post` blocks rather than one container). `looksLikeArticleUrl()` reverts to the plain date-path regex, dropping the `/live-news/` exclusion — no longer needed, since `extract()` will now succeed for live-blogs under normal conditions rather than always returning `null`.

**Tech Stack:** Java 19, Jsoup (already a dependency), JUnit 5, AssertJ, Jackson `ObjectMapper` (existing project stack — no new dependencies).

## Global Constraints

- The standard-article extraction path (when `articleBody` is present) must remain byte-for-byte behaviorally unchanged — verified by the existing `addContentExtractsCNNArticleBodyFromRealExtensionPayloadShape` test continuing to pass without modification.
- The front-page discard path must remain unchanged — verified by the existing `addContentDiscardsCNNFrontPage` test continuing to pass without modification (the front page has zero `p[data-component-name=paragraph]` elements anywhere, confirmed during design investigation, so the document-wide fallback still correctly finds nothing there).
- `looksLikeArticleUrl()` must return to the plain `ARTICLE_URL_PATTERN.matcher(url).find()` check — the `/live-news/` exclusion is removed entirely.
- Live-blog post sub-headlines (`<h2 class="live-story-post__headline">`) are not captured — accepted limitation, same category as the standard path's existing subheading omission.
- Any test that exercises the full `DefaultDigitalMeStorage.addContent()` pipeline must build its HTML fixture via `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html)`, per the established project convention.
- Any test asserting multiple distinct pieces of extracted content must assert on *each* distinct piece individually (not just one representative piece) — a prior feature's task review caught a test that only proved half of a multi-part extraction actually worked; this plan's live-blog tests assert on content from both of the fixture's two separate post blocks.
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: Broaden `CNNPageHandler.extract()` and revert `looksLikeArticleUrl()`

**Files:**
- Modify: `src/main/java/com/breynisson/router/extract/CNNPageHandler.java`
- Modify: `src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java`

**Interfaces:**
- No signature changes to `PageHandler` or `CNNPageHandler` — only the internal behavior of `extract()` and `looksLikeArticleUrl()` changes.

- [ ] **Step 1: Write the failing tests**

Replace `src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java` with this exact content (removes the now-obsolete `doesNotLookLikeArticleUrlForLiveBlog` test, adds `extractsHeadlineAndBodyFromLiveBlogPage` and `looksLikeArticleUrlForLiveBlog`, keeps all other existing tests unchanged):

```java
package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CNNPageHandlerTest {

    private final CNNPageHandler handler = new CNNPageHandler();

    @Test
    void matchesCNNUrl() {
        assertThat(handler.matches("https://edition.cnn.com/2026/07/15/science/new-jersey-fireball-rare-meteorite")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void extractsHeadlineAndBodyFromArticlePage() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline__text" id="maincontent">Meteorite Sheds Light on Ancient Water</h1>
                <div class="article__content" itemprop="articleBody">
                  <p data-component-name="paragraph">A meteorite that crashed through the roof of a home could shed light on ancient water in the solar system.</p>
                  <div data-component-name="related-content" class="related-content">
                    <p class="related-content__headline">Related article: Scientists found something else in an asteroid</p>
                  </div>
                  <p data-component-name="paragraph">Only one fragment was recovered from the meteorite.</p>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Meteorite Sheds Light on Ancient Water");
        assertThat(result).contains("shed light on ancient water in the solar system");
        assertThat(result).contains("Only one fragment was recovered from the meteorite.");
        assertThat(result).doesNotContain("Scientists found something else");
    }

    @Test
    void extractsHeadlineAndBodyFromLiveBlogPage() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline_live-story__text" id="maincontent">Iran War Live Updates</h1>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Iran signals openness to diplomacy</h2>
                  <p data-component-name="paragraph">Officials say Iran remains open to diplomatic talks despite recent tensions.</p>
                </article>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Trump responds to latest developments</h2>
                  <p data-component-name="paragraph">The president addressed reporters about the ongoing situation in the region.</p>
                </article>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Iran War Live Updates");
        assertThat(result).contains("Officials say Iran remains open to diplomatic talks despite recent tensions.");
        assertThat(result).contains("The president addressed reporters about the ongoing situation in the region.");
    }

    @Test
    void returnsNullWhenNoArticleBodyPresent() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <div class="zone">
                  <a href="/2026/07/15/science/some-article"><span class="headline">Meteorite Sheds Light on Ancient Water</span></a>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }

    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com/2026/07/15/science/new-jersey-fireball-rare-meteorite")).isTrue();
    }

    @Test
    void looksLikeArticleUrlForLiveBlog() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com")).isFalse();
    }

    @Test
    void siteNameIsCnn() {
        assertThat(handler.siteName()).isEqualTo("cnn");
    }
}
```

- [ ] **Step 2: Run tests to verify the new/changed ones fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=CNNPageHandlerTest`
Expected: `extractsHeadlineAndBodyFromLiveBlogPage` FAILS (today's `extract()` returns `null` unconditionally when `articleBody` is absent, so the assertions on extracted text fail against a `null` result) and `looksLikeArticleUrlForLiveBlog` FAILS (today's `looksLikeArticleUrl()` has the `/live-news/` exclusion, so it currently returns `false` for this URL, not `true`). All other tests in the file continue to pass unchanged.

- [ ] **Step 3: Apply the fix**

Replace `src/main/java/com/breynisson/router/extract/CNNPageHandler.java` with this exact content:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.regex.Pattern;

public class CNNPageHandler implements PageHandler {

    private static final Pattern ARTICLE_URL_PATTERN = Pattern.compile("/\\d{4}/\\d{2}/\\d{2}/");

    @Override
    public boolean matches(String url) {
        return url.contains("cnn.com");
    }

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

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return ARTICLE_URL_PATTERN.matcher(url).find();
    }

    @Override
    public String siteName() {
        return "cnn";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=CNNPageHandlerTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0` — all tests pass, including `extractsHeadlineAndBodyFromArticlePage` (proving the standard-article path is unaffected) and `returnsNullWhenNoArticleBodyPresent` (proving the discard path is unaffected).

- [ ] **Step 5: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/CNNPageHandler.java src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: extract CNN live-blog content instead of discarding it"
```

---

### Task 2: End-to-end verification through `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `CNNPageHandler.extract()`'s new two-tier behavior (Task 1); no production code changes — `DefaultDigitalMeStorage` already calls the handler generically.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the file's existing `request(...)`/`cleanupDb(...)` helpers). The fixture is built via `ObjectMapper.writeValueAsString(html)` to reproduce the real Chrome-extension payload shape, per this plan's Global Constraints — and asserts on content from **both** separate post blocks individually, per the same constraint:

```java
    @Test
    void addContentExtractsCNNLiveBlogFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline_live-story__text" id="maincontent">Iran War Live Updates</h1>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Iran signals openness to diplomacy</h2>
                  <p data-component-name="paragraph">Officials say Iran remains open to diplomatic talks despite recent tensions.</p>
                </article>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Trump responds to latest developments</h2>
                  <p data-component-name="paragraph">The president addressed reporters about the ongoing situation in the region.</p>
                </article>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump", "Iran War Live Updates", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("diplomatic").results().size());
        assertEquals(1, storage.search("reporters").results().size());
        assertFalse(TextEntryDao.findByName("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump").isEmpty());

        cleanupDb("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: `addContentExtractsCNNLiveBlogFromRealExtensionPayloadShape` FAILS against the pre-Task-1 code — `extract()` returns `null` unconditionally when `articleBody` is absent, so the submission is discarded, `search("diplomatic")` and `search("reporters")` both return 0 results, and `TextEntryDao.findByName(...)` is empty. (If you're implementing Task 1 and Task 2 in sequence, Task 1 is already committed by the time you reach this step, in which case this test should already PASS — that's fine, it's still proof the mechanism works end-to-end; just don't skip actually running it.)

- [ ] **Step 3: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions — in particular `addContentExtractsCNNArticleBodyFromRealExtensionPayloadShape` and `addContentDiscardsCNNFrontPage` must still pass completely unmodified.

- [ ] **Step 4: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "test: verify CNN live-blog extraction end-to-end through addContent"
```

---

### Task 3: Document the live-blog extraction behavior

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `CNNPageHandler` bullet**

In `docs/architecture.md`, the `CNNPageHandler` bullet currently reads:

```markdown
- `CNNPageHandler` — matches any `cnn.com` URL; extracts the `h1` headline plus `p[data-component-name=paragraph]` elements scoped within `div[itemprop=articleBody]`, excluding embedded related-content teaser cards (which don't carry that attribute). Returns `null` when no `articleBody` element is present, covering the front page and other non-article pages. Does not capture subheadings (`data-component-name="subheader"`) — a known, accepted content-completeness limitation, same category as `FotboltiPageHandler`'s `<p>`-only extraction
```

Replace it with:

```markdown
- `CNNPageHandler` — matches any `cnn.com` URL; if `div[itemprop=articleBody]` is present (standard articles), extracts the `h1` headline plus `p[data-component-name=paragraph]` elements scoped within it, excluding embedded related-content teaser cards (which don't carry that attribute). CNN live-blog pages (`/live-news/...`) don't use `articleBody` at all — their content is spread across many separate `live-story-post` blocks instead — so when `articleBody` is absent, the same paragraph selector is searched document-wide instead, picking up all of them. Returns `null` only when neither form of that selector finds anything, covering the front page and other non-content pages. Does not capture subheadings (`data-component-name="subheader"` on standard articles, or each live-blog post's own `<h2>` sub-headline) — a known, accepted content-completeness limitation, same category as `FotboltiPageHandler`'s `<p>`-only extraction
```

- [ ] **Step 2: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document CNN live-blog extraction behavior"
```

---

### Task 4: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no new violations (the project has 9 pre-existing violations in files this branch doesn't touch — confirm via `git diff main...feature/cnn_handler --stat` on those exact paths showing an empty diff).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/cnn_handler --stat`
Expected: shows the original CNN handler feature's files, the earlier live-blog-exclusion fix, and this plan's changes to `CNNPageHandler.java`, `CNNPageHandlerTest.java`, `DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus this plan and its spec under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST the real live-blog HTML (fetch `https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump` or whatever the current live-blog URL is) to `/addContent` directly and confirm via `/search?keywords=...` that a search for a word from one of the live updates finds it. Then confirm the earlier CNN article and front-page manual checks (from the original CNN handler feature) still behave as before.
