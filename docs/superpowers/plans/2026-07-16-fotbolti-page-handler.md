# Fotbolti.net Page Extraction Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third `PageHandler`, `FotboltiPageHandler`, that extracts clean article text (headline + body, including both halves of the CMS's split-body pattern) from fotbolti.net pages, discarding non-article pages the same way the previous two handlers do — further validating that the registry design is genuinely reusable, this time against a third CMS/markup style (Astro + Tailwind, with body text split across two DOM elements around an inline related-article card).

**Architecture:** `FotboltiPageHandler implements PageHandler` (package `com.breynisson.router.extract`, alongside `VisirPageHandler` and `DVPageHandler`): matches any `fotbolti.net` URL, selects all `div.font-body.text-base.leading-8` elements — this single selector matches both halves of an article's split body (an intro-paragraph div and a second div carrying an additional `article-html` class), and nothing else on the page. An empty selection result doubles as the discard signal (no separate existence check needed, unlike the previous two handlers). When non-empty, extracts `h1` + the joined text of all matched divs via Jsoup's `Elements.text()`. One line is added to `PageHandlers`'s existing `HANDLERS` list. No changes to `DefaultDigitalMeStorage`, `PageHandler`, `VisirPageHandler`, or `DVPageHandler` — the registry wiring and the JSON-decoding fix already apply generically to any registered handler.

**Tech Stack:** Java 19, Jsoup (already a dependency), JUnit 5, AssertJ, Jackson `ObjectMapper` (existing project stack — no new dependencies).

## Global Constraints

- Domain matching uses simple substring containment (`url.contains("fotbolti.net")`), matching the existing convention in `ScreenshotCoverage`, `VisirPageHandler`, and `DVPageHandler`.
- `PageHandler.extract(Document)` returning `null` is the sole discard signal — no separate "is this the front page" check.
- The body selector must be `div.font-body.text-base.leading-8` (all three classes required) — this is the one combination confirmed (by inspecting real fetched HTML) to match both halves of an article's split body and nothing elsewhere on the page. Do not narrow it to a single div or attempt to reconstruct paragraph order via sibling traversal — the combined `Elements.text()` join of all matches is the intended design.
- No other site handlers are added or modified in this change — only `FotboltiPageHandler` is added, and the only change to `PageHandlers` is the one new list entry.
- No changes to the Chrome extension, `DefaultDigitalMeStorage`, `PageHandler`, `VisirPageHandler`, or `DVPageHandler`.
- Any test that exercises the full `DefaultDigitalMeStorage.addContent()` pipeline (not just `FotboltiPageHandler.extract()` in isolation) must build its HTML fixture via `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html)`, reproducing the real Chrome-extension JSON-escaped payload shape — not clean HTML — per the established project convention (a prior feature found and fixed a Critical bug where attribute/class-based selectors silently fail against clean-HTML-only test fixtures). Unit tests of `FotboltiPageHandler.extract()` in isolation, which take an already-parsed `Document` and never touch `DefaultDigitalMeStorage`, use clean HTML fixtures as normal.
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `FotboltiPageHandler`

**Files:**
- Create: `src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java`
- Test: `src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java`

**Interfaces:**
- Produces: `FotboltiPageHandler implements PageHandler` (interface already exists: `boolean matches(String url)`, `String extract(org.jsoup.nodes.Document doc)`, null return = discard). Used by Task 2's `PageHandlers` registry.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FotboltiPageHandlerTest {

    private final FotboltiPageHandler handler = new FotboltiPageHandler();

    @Test
    void matchesFotboltiUrl() {
        assertThat(handler.matches("https://fotbolti.net/news/16-07-2026/otrulegir-yfirburdur-argentinu")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void extractsHeadlineAndBothBodyPartsFromArticlePage() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="p-(--space-4)">
                  <h1 class="font-heading text-2xl font-bold uppercase leading-tight text-text-primary lg:text-3xl">Argentina Dominates After England Takes Lead</h1>
                  <div class="space-y-(--space-4)">
                    <div class="font-body text-base leading-8 text-text-primary">The manager faced heavy criticism after the team lost in the semi-final.</div>
                    <div class="lg:hidden"></div>
                    <a href="/news/other-article" class="flex overflow-hidden">
                      <h3 class="mt-(--space-1) text-base leading-normal">Related: Manager Defends His Decisions</h3>
                    </a>
                    <div class="article-html font-body text-base leading-8 text-text-primary">Statistics show a complete turnaround after the opening goal was scored.</div>
                  </div>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Argentina Dominates After England Takes Lead");
        assertThat(result).contains("The manager faced heavy criticism after the team lost in the semi-final.");
        assertThat(result).contains("Statistics show a complete turnaround after the opening goal was scored.");
        assertThat(result).doesNotContain("Related: Manager Defends His Decisions");
    }

    @Test
    void returnsNullWhenNoMatchingBodyDivsPresent() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="space-y-(--space-2)">
                  <a href="/news/1"><span class="line-clamp-2">Argentina Dominates After England Takes Lead</span></a>
                  <a href="/news/2"><span class="line-clamp-2">Another Story Headline</span></a>
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

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=FotboltiPageHandlerTest`
Expected: compile failure — `FotboltiPageHandler` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class FotboltiPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("fotbolti.net");
    }

    @Override
    public String extract(Document doc) {
        Elements paragraphs = doc.select("div.font-body.text-base.leading-8");
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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=FotboltiPageHandlerTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add FotboltiPageHandler to extract clean article text from fotbolti.net pages"
```

---

### Task 2: Register `FotboltiPageHandler` in `PageHandlers`

**Files:**
- Modify: `src/main/java/com/breynisson/router/extract/PageHandlers.java`
- Modify: `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`

**Interfaces:**
- Consumes: `FotboltiPageHandler` (Task 1).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/breynisson/router/extract/PageHandlersTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void findsFotboltiHandlerForFotboltiUrl() {
        assertThat(PageHandlers.find("https://fotbolti.net/news/16-07-2026/some-article"))
                .containsInstanceOf(FotboltiPageHandler.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `findsFotboltiHandlerForFotboltiUrl` FAILS — today's `PageHandlers.HANDLERS` only contains `VisirPageHandler` and `DVPageHandler`, so `find()` returns empty for a fotbolti.net URL.

- [ ] **Step 3: Register the handler**

In `src/main/java/com/breynisson/router/extract/PageHandlers.java`, the current content reads:

```java
package com.breynisson.router.extract;

import java.util.List;
import java.util.Optional;

public final class PageHandlers {

    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DVPageHandler());

    private PageHandlers() {
    }

    public static Optional<PageHandler> find(String url) {
        return HANDLERS.stream().filter(handler -> handler.matches(url)).findFirst();
    }
}
```

Change the `HANDLERS` line to:

```java
    private static final List<PageHandler> HANDLERS = List.of(new VisirPageHandler(), new DVPageHandler(), new FotboltiPageHandler());
```

No other changes to this file.

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0` (the three pre-existing tests plus the new one).

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandlers.java src/test/java/com/breynisson/router/extract/PageHandlersTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: register FotboltiPageHandler in PageHandlers"
```

---

### Task 3: End-to-end verification through `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `PageHandlers.find()` resolving to `FotboltiPageHandler` (Task 2); no production code changes — `DefaultDigitalMeStorage` already calls the registry generically.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the file's existing `request(...)`/`cleanupDb(...)` helpers). Both fixtures are built via `ObjectMapper.writeValueAsString(html)` to reproduce the real Chrome-extension payload shape, per this plan's Global Constraints:

```java
    @Test
    void addContentExtractsFotboltiArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="p-(--space-4)">
                  <h1 class="font-heading text-2xl font-bold uppercase leading-tight text-text-primary lg:text-3xl">Argentina Dominates After England Takes Lead</h1>
                  <div class="space-y-(--space-4)">
                    <div class="font-body text-base leading-8 text-text-primary">The manager faced heavy criticism after the team lost in the semi-final.</div>
                    <div class="lg:hidden"></div>
                    <a href="/news/other-article" class="flex overflow-hidden">
                      <h3 class="mt-(--space-1) text-base leading-normal">Related: Manager Defends His Decisions</h3>
                    </a>
                    <div class="article-html font-body text-base leading-8 text-text-primary">Statistics show a complete turnaround after the opening goal was scored.</div>
                  </div>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://fotbolti.net/news/16-07-2026/argentina-dominates", "Argentina Dominates", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("turnaround").results().size());
        assertTrue(storage.search("Defends").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://fotbolti.net/news/16-07-2026/argentina-dominates").isEmpty());

        cleanupDb("https://fotbolti.net/news/16-07-2026/argentina-dominates");
    }

    @Test
    void addContentDiscardsFotboltiFrontPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://fotbolti.net");
        storage.addContent(request("http://unrelated-fotbolti-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="space-y-(--space-2)">
                  <a href="/news/1"><span class="line-clamp-2">Argentina Dominates After England Takes Lead</span></a>
                  <a href="/news/2"><span class="line-clamp-2">Another Story Headline</span></a>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://fotbolti.net", "Fotbolti Front Page", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://fotbolti.net").isEmpty());
        assertTrue(storage.search("Dominates").results().isEmpty());

        cleanupDb("http://unrelated-fotbolti-seed.com");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: both new tests should already PASS at this point, since Tasks 1-2 already wired `FotboltiPageHandler` into the registry and `DefaultDigitalMeStorage` already consumes the registry generically (no production code left to change). If either test fails, that's a signal Task 1 or Task 2's implementation has a bug — investigate before proceeding rather than treating this as an expected RED step. (Unlike Tasks 1-2, this task has no production-code step of its own — it exists purely to prove the full pipeline, decode-then-extract-then-index, works end-to-end for a third handler.)

- [ ] **Step 3: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "test: verify FotboltiPageHandler end-to-end through addContent"
```

---

### Task 4: Document `FotboltiPageHandler`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler` subsystem section**

In `docs/architecture.md`, find the section (already documenting the first two handlers):

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler`
- Located in `extract/` package, alongside `YouTubeCaptionExtractor`
- `PageHandler` interface: `matches(url)` decides if a handler applies; `extract(Document)` returns the clean extracted text, or `null` to signal the submission has nothing worth indexing (discarded the same way as a `ScreenshotCoverage` match)
- `PageHandlers.find(url)` — static registry; returns the first matching handler, or empty if none apply (falls through to the generic Jsoup strip / YouTube extraction)
- `VisirPageHandler` — matches any `visir.is` URL; extracts the `h1` headline plus `div[itemprop=articleBody]` text, skipping all nav/related-article/footer markup. Returns `null` when no `articleBody` element is present, which covers the front page and other non-article pages (section fronts, live-blog hubs) without a separate root-URL check
- `DVPageHandler` — matches any `dv.is` URL; extracts the `h1` headline plus the direct-child `<p>` paragraphs of `div.article-body .field--name-body` (dv.is is Drupal-based, and `field--name-body` alone is not article-specific — it's reused for footer/sidebar widgets, so selection is scoped under the article-specific `div.article-body` wrapper, and direct-child-only paragraph selection excludes embedded image blocks). Returns `null` when no `article-body` element is present, covering the front page and other non-article pages
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

Rename the heading and add a `FotboltiPageHandler` bullet, so it reads:

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler` / `DVPageHandler` / `FotboltiPageHandler`
- Located in `extract/` package, alongside `YouTubeCaptionExtractor`
- `PageHandler` interface: `matches(url)` decides if a handler applies; `extract(Document)` returns the clean extracted text, or `null` to signal the submission has nothing worth indexing (discarded the same way as a `ScreenshotCoverage` match)
- `PageHandlers.find(url)` — static registry; returns the first matching handler, or empty if none apply (falls through to the generic Jsoup strip / YouTube extraction)
- `VisirPageHandler` — matches any `visir.is` URL; extracts the `h1` headline plus `div[itemprop=articleBody]` text, skipping all nav/related-article/footer markup. Returns `null` when no `articleBody` element is present, which covers the front page and other non-article pages (section fronts, live-blog hubs) without a separate root-URL check
- `DVPageHandler` — matches any `dv.is` URL; extracts the `h1` headline plus the direct-child `<p>` paragraphs of `div.article-body .field--name-body` (dv.is is Drupal-based, and `field--name-body` alone is not article-specific — it's reused for footer/sidebar widgets, so selection is scoped under the article-specific `div.article-body` wrapper, and direct-child-only paragraph selection excludes embedded image blocks). Returns `null` when no `article-body` element is present, covering the front page and other non-article pages
- `FotboltiPageHandler` — matches any `fotbolti.net` URL; extracts the `h1` headline plus all `div.font-body.text-base.leading-8` elements' joined text (fotbolti.net's CMS splits an article's body into two such divs around an inline "related article" card — this single selector matches both halves and nothing else on the page). Returns `null` when the selection is empty, which covers the front page and other non-article pages; unlike the other two handlers, there's no separate existence check — the extraction selector and the discard check are the same query
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

- [ ] **Step 2: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document FotboltiPageHandler"
```

---

### Task 5: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no new violations (the project has 9 pre-existing violations in files this branch doesn't touch — `IndexPage.java`, `TranscriptListExtractor.java`, `FileChangeWatcherTest.java` — confirm via `git diff main...feature/fotbolti_handler --stat` on those exact paths showing an empty diff).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/fotbolti_handler --stat`
Expected: shows changes to `src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java` (new), `src/main/java/com/breynisson/router/extract/PageHandlers.java`, `src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java` (new), `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`, `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST the real fotbolti.net article HTML (fetch the article URL given for this feature, or any current fotbolti.net article) to `/addContent` directly and confirm via `/search?keywords=...` that a search for a word from either half of the split article body finds it, while a search for text only present in an inline "Tengt efni" related-article card does not. Then POST the fotbolti.net front page HTML and confirm nothing gets indexed.
