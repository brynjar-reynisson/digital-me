# CNN Page Extraction Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fourth `PageHandler`, `CNNPageHandler`, that extracts clean article text (headline + body) from edition.cnn.com pages, discarding non-article pages the same way the previous three handlers do — the first handler added since the `looksLikeArticleUrl()`/`siteName()` interface additions shipped, so it validates that "add a new site = one class + one registry line" still holds after that change.

**Architecture:** `CNNPageHandler implements PageHandler` (package `com.breynisson.router.extract`, alongside `VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`): matches any `cnn.com` URL, selects `div[itemprop=articleBody]` as the discard signal (absent → `null`, same schema.org marker `VisirPageHandler` already relies on), and when present extracts `h1` + `p[data-component-name=paragraph]` elements scoped within that body (excluding embedded related-content teaser cards, which don't carry that attribute), joined via Jsoup's `Elements.text()`. `looksLikeArticleUrl()` matches CNN's `/YYYY/MM/DD/` URL path shape; `siteName()` returns `"cnn"`. One line is added to `PageHandlers`'s existing `HANDLERS` list. No changes to `DefaultDigitalMeStorage`, `PageHandler`, `LayoutChangeReporter`, or the other three handlers — the registry wiring, the JSON-decoding fix, and the layout-change fallback/report mechanism already apply generically to any registered handler.

**Tech Stack:** Java 19, Jsoup (already a dependency), JUnit 5, AssertJ, Jackson `ObjectMapper` (existing project stack — no new dependencies).

## Global Constraints

- Domain matching uses simple substring containment (`url.contains("cnn.com")`), matching the existing convention in `ScreenshotCoverage` and the other three handlers.
- `PageHandler.extract(Document)` returning `null` is the sole discard signal (or, per the already-shipped layout-error-handling mechanism, a "layout changed" signal when combined with `looksLikeArticleUrl()` returning `true` for the submitted URL — no new logic is needed in `CNNPageHandler` itself for that; it's handled centrally).
- `looksLikeArticleUrl(url)` must match the regex `/\d{4}/\d{2}/\d{2}/` (CNN's `/YYYY/MM/DD/` article path shape).
- `siteName()` must return `"cnn"`.
- Paragraph selection must be scoped to `p[data-component-name=paragraph]` within the found `articleBody` element — not the whole body's `.text()` — to exclude embedded related-content teaser cards' own headline/caption text, which don't carry that attribute.
- No other site handlers are added or modified in this change — only `CNNPageHandler` is added, and the only change to `PageHandlers` is the one new list entry.
- No changes to the Chrome extension, `DefaultDigitalMeStorage`, `PageHandler`, `LayoutChangeReporter`, or the other three handlers.
- Any test that exercises the full `DefaultDigitalMeStorage.addContent()` pipeline (not just `CNNPageHandler.extract()` in isolation) must build its HTML fixture via `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html)`, reproducing the real Chrome-extension JSON-escaped payload shape — not clean HTML — per the established project convention. Unit tests of `CNNPageHandler.extract()` in isolation use clean HTML fixtures as normal.
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `CNNPageHandler`

**Files:**
- Create: `src/main/java/com/breynisson/router/extract/CNNPageHandler.java`
- Test: `src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java`

**Interfaces:**
- Produces: `CNNPageHandler implements PageHandler` (interface already has 4 methods: `matches`, `extract`, `looksLikeArticleUrl`, `siteName` — all implemented here, since this is a brand-new class, not a partial migration). Used by Task 2's `PageHandlers` registry.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java`:

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
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com")).isFalse();
    }

    @Test
    void siteNameIsCnn() {
        assertThat(handler.siteName()).isEqualTo("cnn");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=CNNPageHandlerTest`
Expected: compile failure — `CNNPageHandler` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/extract/CNNPageHandler.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
        if (articleBody == null) {
            return null;
        }
        String paragraphs = articleBody.select("p[data-component-name=paragraph]").text();
        Element headline = doc.selectFirst("h1");
        if (headline == null) {
            return paragraphs;
        }
        return headline.text() + "\n\n" + paragraphs;
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

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=CNNPageHandlerTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/CNNPageHandler.java src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add CNNPageHandler to extract clean article text from edition.cnn.com pages"
```

---

### Task 2: Register `CNNPageHandler` in `PageHandlers`

**Files:**
- Modify: `src/main/java/com/breynisson/router/extract/PageHandlers.java`
- Modify: `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`

**Interfaces:**
- Consumes: `CNNPageHandler` (Task 1).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/breynisson/router/extract/PageHandlersTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void findsCNNHandlerForCNNUrl() {
        assertThat(PageHandlers.find("https://edition.cnn.com/2026/07/15/science/some-article"))
                .containsInstanceOf(CNNPageHandler.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `findsCNNHandlerForCNNUrl` FAILS — today's `PageHandlers.HANDLERS` only contains `VisirPageHandler`, `DVPageHandler`, `FotboltiPageHandler`, so `find()` returns empty for a cnn.com URL.

- [ ] **Step 3: Register the handler**

In `src/main/java/com/breynisson/router/extract/PageHandlers.java`, the current content reads:

```java
package com.breynisson.router.extract;

import java.util.List;
import java.util.Optional;

public final class PageHandlers {

    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DVPageHandler(), new FotboltiPageHandler());

    private PageHandlers() {
    }

    public static Optional<PageHandler> find(String url) {
        return HANDLERS.stream().filter(handler -> handler.matches(url)).findFirst();
    }
}
```

Change the `HANDLERS` line to:

```java
    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DVPageHandler(), new FotboltiPageHandler(), new CNNPageHandler());
```

No other changes to this file.

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0` (the four pre-existing tests plus the new one).

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandlers.java src/test/java/com/breynisson/router/extract/PageHandlersTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: register CNNPageHandler in PageHandlers"
```

---

### Task 3: End-to-end verification through `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `PageHandlers.find()` resolving to `CNNPageHandler` (Task 2); no production code changes — `DefaultDigitalMeStorage` already calls the registry generically.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the file's existing `request(...)`/`cleanupDb(...)` helpers). Both fixtures are built via `ObjectMapper.writeValueAsString(html)` to reproduce the real Chrome-extension payload shape, per this plan's Global Constraints:

```java
    @Test
    void addContentExtractsCNNArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
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
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://edition.cnn.com/2026/07/15/science/meteorite-ancient-water", "Meteorite", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("fragment").results().size());
        assertTrue(storage.search("Scientists found something else").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://edition.cnn.com/2026/07/15/science/meteorite-ancient-water").isEmpty());

        cleanupDb("https://edition.cnn.com/2026/07/15/science/meteorite-ancient-water");
    }

    @Test
    void addContentDiscardsCNNFrontPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://edition.cnn.com");
        storage.addContent(request("http://unrelated-cnn-seed.com", "Unrelated", "unrelated seed content"));

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
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://edition.cnn.com", "CNN Front Page", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://edition.cnn.com").isEmpty());
        assertTrue(storage.search("Meteorite Sheds Light").results().isEmpty());

        cleanupDb("http://unrelated-cnn-seed.com");
    }
```

Note: `https://edition.cnn.com` does not match `CNNPageHandler.looksLikeArticleUrl()` (no `/YYYY/MM/DD/` path), so this discard test exercises the genuine "not an article" discard path — not the layout-change fallback path.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: both new tests should already PASS at this point, since Tasks 1-2 already wired `CNNPageHandler` into the registry and `DefaultDigitalMeStorage` already consumes the registry generically (no production code left to change). If either test fails, that's a signal Task 1 or Task 2's implementation has a bug — investigate before proceeding rather than treating this as an expected RED step.

- [ ] **Step 3: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "test: verify CNNPageHandler end-to-end through addContent"
```

---

### Task 4: Document `CNNPageHandler`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `PageHandler` subsystem section**

In `docs/architecture.md`, find the section heading documenting the existing handlers (currently `### \`PageHandler\` / \`PageHandlers\` / \`VisirPageHandler\` / \`DVPageHandler\` / \`FotboltiPageHandler\``). Rename the heading and add a `CNNPageHandler` bullet right after the `FotboltiPageHandler` bullet (before the `looksLikeArticleUrl`/`siteName`/"To add a new site" bullets), so the heading reads:

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler` / `FotboltiPageHandler` / `CNNPageHandler`
```

and the new bullet reads:

```markdown
- `CNNPageHandler` — matches any `cnn.com` URL; extracts the `h1` headline plus `p[data-component-name=paragraph]` elements scoped within `div[itemprop=articleBody]`, excluding embedded related-content teaser cards (which don't carry that attribute). Returns `null` when no `articleBody` element is present, covering the front page and other non-article pages. Does not capture subheadings (`data-component-name="subheader"`) — a known, accepted content-completeness limitation, same category as `FotboltiPageHandler`'s `<p>`-only extraction
```

- [ ] **Step 2: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document CNNPageHandler"
```

---

### Task 5: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no new violations (the project has 9 pre-existing violations in files this branch doesn't touch — `IndexPage.java`, `TranscriptListExtractor.java`, `FileChangeWatcherTest.java` — confirm via `git diff main...feature/cnn_handler --stat` on those exact paths showing an empty diff).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/cnn_handler --stat`
Expected: shows changes to `src/main/java/com/breynisson/router/extract/CNNPageHandler.java` (new), `src/main/java/com/breynisson/router/extract/PageHandlers.java`, `src/test/java/com/breynisson/router/extract/CNNPageHandlerTest.java` (new), `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`, `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST the real CNN article HTML (fetch the article URL given for this feature, or any current edition.cnn.com article) to `/addContent` directly and confirm via `/search?keywords=...` that a search for a word from the article body finds it, while a search for text only present in an embedded related-content card does not. Then POST the CNN front page HTML and confirm nothing gets indexed.
