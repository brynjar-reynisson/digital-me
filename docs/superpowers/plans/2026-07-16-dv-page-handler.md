# DV.is Page Extraction Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second `PageHandler`, `DvPageHandler`, that extracts clean article text (headline + body paragraphs) from dv.is pages, discarding non-article pages the same way `VisirPageHandler` does — validating that the existing registry design is genuinely reusable for a new site with a different CMS and no other code changes.

**Architecture:** `DvPageHandler implements PageHandler` (package `com.breynisson.router.extract`, alongside `VisirPageHandler`): matches any `dv.is` URL, selects `div.article-body` as the discard signal (absent → `null`, covering the front page and other non-article pages), and when present extracts `h1` + the direct-child `<p>` paragraphs of `div.article-body .field--name-body` (excluding embedded image blocks). One line is added to `PageHandlers`'s existing `HANDLERS` list. No changes to `DefaultDigitalMeStorage`, `PageHandler`, or `VisirPageHandler` — the registry wiring and the JSON-decoding fix (from the visir.is feature) already apply generically to any registered handler.

**Tech Stack:** Java 19, Jsoup (already a dependency), JUnit 5, AssertJ, Jackson `ObjectMapper` (existing project stack — no new dependencies).

## Global Constraints

- Domain matching uses simple substring containment (`url.contains("dv.is")`), matching the existing convention in `ScreenshotCoverage` and `VisirPageHandler`.
- `PageHandler.extract(Document)` returning `null` is the sole discard signal — no separate "is this the front page" check.
- Selection must be scoped to `div.article-body` — do NOT select `div.field--name-body` unscoped anywhere, since that class is reused outside articles on dv.is (footer address/copyright blocks, a "Tarot Spil á DV" promo widget, a "tip us" widget) and an unscoped selector would incorrectly match one of those on non-article pages instead of discarding.
- Body-paragraph selection must use direct children only (`div.article-body .field--name-body > p`) to exclude the embedded `<article class="media...">` image blocks, which carry a visually-hidden `"Mynd"` label that must not appear in the extracted text.
- No other site handlers are added or modified in this change — only `DvPageHandler` is added, and the only change to `PageHandlers` is the one new list entry.
- No changes to the Chrome extension, `DefaultDigitalMeStorage`, `PageHandler`, or `VisirPageHandler`.
- Any test that exercises the full `DefaultDigitalMeStorage.addContent()` pipeline (not just `DvPageHandler.extract()` in isolation) must build its HTML fixture via `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html)`, reproducing the real Chrome-extension JSON-escaped payload shape — not clean HTML — since `class="article-body"` is an HTML attribute and would hit the same JSON-escaping trap a prior feature found and fixed if a test only ever validated against already-clean HTML. (Unit tests of `DvPageHandler.extract()` in isolation, which take an already-parsed `Document` and never touch `DefaultDigitalMeStorage`, use clean HTML fixtures as normal — this constraint applies only to `DefaultDigitalMeStorageTest` additions.)
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `DvPageHandler`

**Files:**
- Create: `src/main/java/com/breynisson/router/extract/DvPageHandler.java`
- Test: `src/test/java/com/breynisson/router/extract/DvPageHandlerTest.java`

**Interfaces:**
- Produces: `DvPageHandler implements PageHandler` (interface already exists from the visir.is feature: `boolean matches(String url)`, `String extract(org.jsoup.nodes.Document doc)`, null return = discard). Used by Task 2's `PageHandlers` registry.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/extract/DvPageHandlerTest.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DvPageHandlerTest {

    private final DvPageHandler handler = new DvPageHandler();

    @Test
    void matchesDvUrl() {
        assertThat(handler.matches("https://www.dv.is/433/2026/07/15/storstjarna-faer-a-baukinn")).isTrue();
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
                <nav>Home News Sports Life Opinion</nav>
                <article class="node node--article">
                  <div class="node__content">
                    <div class="article-header">
                      <div class="field field--name-title">
                        <h1>Star Gets Called Out</h1>
                      </div>
                    </div>
                    <div class="article-body photoswipe-gallery">
                      <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                        <p>The player was criticized after photos surfaced from a party.</p>
                        <article class="media media--type-image media--view-mode-default">
                          <div class="field__label visually-hidden">Mynd</div>
                        </article>
                        <p>Fans reacted strongly to the news on social media.</p>
                      </div>
                    </div>
                    <div class="article-footer-region">
                      <h2>Fleiri fréttir</h2>
                      <div class="views-content">Unrelated Story About Something Else</div>
                    </div>
                  </div>
                </article>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Star Gets Called Out");
        assertThat(result).contains("The player was criticized after photos surfaced from a party.");
        assertThat(result).contains("Fans reacted strongly to the news on social media.");
        assertThat(result).doesNotContain("Mynd");
        assertThat(result).doesNotContain("Unrelated Story");
    }

    @Test
    void returnsNullWhenNoArticleBodyPresent() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <div class="tarot-promo"><h4>Tarot Cards</h4></div>
                </div>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <p>Address 123<br>City</p>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DvPageHandlerTest`
Expected: compile failure — `DvPageHandler` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/extract/DvPageHandler.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class DvPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("dv.is");
    }

    @Override
    public String extract(Document doc) {
        Element articleBody = doc.selectFirst("div.article-body");
        if (articleBody == null) {
            return null;
        }
        String paragraphs = doc.select("div.article-body .field--name-body > p").text();
        Element headline = doc.selectFirst("h1");
        if (headline == null) {
            return paragraphs;
        }
        return headline.text() + "\n\n" + paragraphs;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DvPageHandlerTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/DvPageHandler.java src/test/java/com/breynisson/router/extract/DvPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add DvPageHandler to extract clean article text from dv.is pages"
```

---

### Task 2: Register `DvPageHandler` in `PageHandlers`

**Files:**
- Modify: `src/main/java/com/breynisson/router/extract/PageHandlers.java`
- Modify: `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`

**Interfaces:**
- Consumes: `DvPageHandler` (Task 1).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/breynisson/router/extract/PageHandlersTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void findsDvHandlerForDvUrl() {
        assertThat(PageHandlers.find("https://www.dv.is/433/2026/07/15/some-article"))
                .containsInstanceOf(DvPageHandler.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `findsDvHandlerForDvUrl` FAILS — today's `PageHandlers.HANDLERS` only contains `VisirPageHandler`, so `find()` returns empty for a dv.is URL.

- [ ] **Step 3: Register the handler**

In `src/main/java/com/breynisson/router/extract/PageHandlers.java`, the current content reads:

```java
package com.breynisson.router.extract;

import java.util.List;
import java.util.Optional;

public final class PageHandlers {

    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler());

    private PageHandlers() {
    }

    public static Optional<PageHandler> find(String url) {
        return HANDLERS.stream().filter(handler -> handler.matches(url)).findFirst();
    }
}
```

Change the `HANDLERS` line to:

```java
    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DvPageHandler());
```

No other changes to this file.

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` (the two pre-existing tests plus the new one).

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandlers.java src/test/java/com/breynisson/router/extract/PageHandlersTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: register DvPageHandler in PageHandlers"
```

---

### Task 3: End-to-end verification through `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `PageHandlers.find()` resolving to `DvPageHandler` (Task 2); no production code changes — `DefaultDigitalMeStorage` already calls the registry generically.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the file's existing `request(...)`/`cleanupDb(...)` helpers). Both fixtures are built via `ObjectMapper.writeValueAsString(html)` to reproduce the real Chrome-extension payload shape, per this plan's Global Constraints:

```java
    @Test
    void addContentExtractsDvArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <article class="node node--article">
                  <div class="node__content">
                    <div class="article-header">
                      <div class="field field--name-title">
                        <h1>Star Gets Called Out</h1>
                      </div>
                    </div>
                    <div class="article-body photoswipe-gallery">
                      <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                        <p>The player was criticized after photos surfaced from a party.</p>
                        <article class="media media--type-image media--view-mode-default">
                          <div class="field__label visually-hidden">Mynd</div>
                        </article>
                        <p>Fans reacted strongly to the news on social media.</p>
                      </div>
                    </div>
                    <div class="article-footer-region">
                      <h2>Fleiri fréttir</h2>
                      <div class="views-content">Unrelated Story About Something Else</div>
                    </div>
                  </div>
                </article>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.dv.is/433/2026/07/16/star-gets-called-out", "Star Gets Called Out", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("criticized").results().size());
        assertTrue(storage.search("Unrelated").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://www.dv.is/433/2026/07/16/star-gets-called-out").isEmpty());

        cleanupDb("https://www.dv.is/433/2026/07/16/star-gets-called-out");
    }

    @Test
    void addContentDiscardsDvFrontPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://www.dv.is");
        storage.addContent(request("http://unrelated-dv-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <div class="tarot-promo"><h4>Tarot Cards</h4></div>
                </div>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <p>Address 123<br>City</p>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.dv.is", "DV Front Page", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://www.dv.is").isEmpty());
        assertTrue(storage.search("Tarot").results().isEmpty());

        cleanupDb("http://unrelated-dv-seed.com");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: both new tests should already PASS at this point, since Tasks 1-2 already wired `DvPageHandler` into the registry and `DefaultDigitalMeStorage` already consumes the registry generically (no production code left to change). If either test fails, that's a signal Task 1 or Task 2's implementation has a bug — investigate before proceeding rather than treating this as an expected RED step. (Unlike Tasks 1-2, this task has no production-code step of its own — it exists purely to prove the full pipeline, decode-then-extract-then-index, works end-to-end for a second handler.)

- [ ] **Step 3: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "test: verify DvPageHandler end-to-end through addContent"
```

---

### Task 4: Document `DvPageHandler`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `PageHandler` / `PageHandlers` / `VisirPageHandler` subsystem section**

In `docs/architecture.md`, find the section (added by the visir.is feature):

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler`
- Located in `extract/` package, alongside `YouTubeCaptionExtractor`
- `PageHandler` interface: `matches(url)` decides if a handler applies; `extract(Document)` returns the clean extracted text, or `null` to signal the submission has nothing worth indexing (discarded the same way as a `ScreenshotCoverage` match)
- `PageHandlers.find(url)` — static registry; returns the first matching handler, or empty if none apply (falls through to the generic Jsoup strip / YouTube extraction)
- `VisirPageHandler` — matches any `visir.is` URL; extracts the `h1` headline plus `div[itemprop=articleBody]` text, skipping all nav/related-article/footer markup. Returns `null` when no `articleBody` element is present, which covers the front page and other non-article pages (section fronts, live-blog hubs) without a separate root-URL check
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

Rename the heading and add a `DvPageHandler` bullet, so it reads:

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DvPageHandler`
- Located in `extract/` package, alongside `YouTubeCaptionExtractor`
- `PageHandler` interface: `matches(url)` decides if a handler applies; `extract(Document)` returns the clean extracted text, or `null` to signal the submission has nothing worth indexing (discarded the same way as a `ScreenshotCoverage` match)
- `PageHandlers.find(url)` — static registry; returns the first matching handler, or empty if none apply (falls through to the generic Jsoup strip / YouTube extraction)
- `VisirPageHandler` — matches any `visir.is` URL; extracts the `h1` headline plus `div[itemprop=articleBody]` text, skipping all nav/related-article/footer markup. Returns `null` when no `articleBody` element is present, which covers the front page and other non-article pages (section fronts, live-blog hubs) without a separate root-URL check
- `DvPageHandler` — matches any `dv.is` URL; extracts the `h1` headline plus the direct-child `<p>` paragraphs of `div.article-body .field--name-body` (dv.is is Drupal-based, and `field--name-body` alone is not article-specific — it's reused for footer/sidebar widgets, so selection is scoped under the article-specific `div.article-body` wrapper, and direct-child-only paragraph selection excludes embedded image blocks). Returns `null` when no `article-body` element is present, covering the front page and other non-article pages
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

- [ ] **Step 2: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document DvPageHandler"
```

---

### Task 5: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no new violations (the project has 9 pre-existing violations in files this branch doesn't touch — `IndexPage.java`, `TranscriptListExtractor.java`, `FileChangeWatcherTest.java` — confirm via `git diff main...feature/dv_handler --stat` on those exact paths showing an empty diff).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/dv_handler --stat`
Expected: shows changes to `src/main/java/com/breynisson/router/extract/DvPageHandler.java` (new), `src/main/java/com/breynisson/router/extract/PageHandlers.java`, `src/test/java/com/breynisson/router/extract/DvPageHandlerTest.java` (new), `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`, `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST the real dv.is article HTML (fetch the article URL given for this feature, or any current dv.is article) to `/addContent` directly and confirm via `/search?keywords=...` that a search for a word from the article body finds it, while a search for text only present in the "Fleiri fréttir" related-articles section does not. Then POST the dv.is front page HTML and confirm nothing gets indexed.
