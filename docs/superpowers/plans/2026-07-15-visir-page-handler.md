# Visir.is Page Extraction Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace generic Jsoup text-stripping with a clean, site-specific extraction for visir.is article pages (headline + article body only, no nav/related-content noise), discard non-article visir.is pages (front page, section fronts) entirely, and do it via a generic, pluggable mechanism so future sites can get their own handler.

**Architecture:** A new `PageHandler` interface (package `com.breynisson.router.extract`) defines `matches(url)` / `extract(Document)`. `VisirPageHandler` implements it: matches any `visir.is` URL, extracts `h1` + `div[itemprop=articleBody]` text, returns `null` (discard) when no `articleBody` element exists — which covers the front page and any other non-article page without a separate root-URL special case. A static `PageHandlers` registry holds all handlers and resolves the one matching a given URL. `DefaultDigitalMeStorage.addContent()` checks the registry (after the existing `ScreenshotCoverage` check, before the YouTube/generic-Jsoup branches) and either uses the handler's extracted text, discards silently (same pattern as `ScreenshotCoverage`), or falls through to existing behavior for non-matching URLs.

**Tech Stack:** Java 19, Jsoup (already a dependency), JUnit 5, AssertJ (existing project stack — no new dependencies).

## Global Constraints

- Domain matching uses simple substring containment (`url.contains("visir.is")`), matching this codebase's existing convention in `ScreenshotCoverage` (`url.contains("facebook.com")`) rather than proper URI/host parsing.
- A discarded submission still returns `success = true` (silent no-op), same as the existing `ScreenshotCoverage` discard path — the Chrome extension's `background.js` POST is fire-and-forget.
- `PageHandler.extract(Document)` returning `null` is the sole discard signal — no separate "is this the front page" check anywhere.
- No other site handlers are added in this change — the registry starts with just `VisirPageHandler`.
- No change to the Chrome extension, MCP resource writing, or embedding indexing — only the extraction step inside `DefaultDigitalMeStorage.addContent()`'s existing HTTP branch changes.
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `PageHandler` interface + `VisirPageHandler`

**Files:**
- Create: `src/main/java/com/breynisson/router/extract/PageHandler.java`
- Create: `src/main/java/com/breynisson/router/extract/VisirPageHandler.java`
- Test: `src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java`

**Interfaces:**
- Produces: `PageHandler` interface with `boolean matches(String url)` and `String extract(org.jsoup.nodes.Document doc)` (null return = discard); `VisirPageHandler implements PageHandler`. Used by Task 2's `PageHandlers` registry and Task 3's `DefaultDigitalMeStorage`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisirPageHandlerTest {

    private final VisirPageHandler handler = new VisirPageHandler();

    @Test
    void matchesVisirUrl() {
        assertThat(handler.matches("https://www.visir.is/g/20262909759d/messi-allt-i-ollu-thegar-argentina-for-i-ur-slita-leikinn")).isTrue();
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
                <nav>Home News Sports Weather Opinion Most Read: Some Other Story</nav>
                <article class="article-single -sport">
                    <header class="article-single__header">
                        <h1>Team Wins Championship Final</h1>
                    </header>
                    <div class="article-single__content">
                        <article>
                            <div itemprop="articleBody">
                                <p>The home team secured a dramatic victory in the final minutes.</p>
                                <p>Fans celebrated wildly across the city.</p>
                            </div>
                        </article>
                    </div>
                </article>
                <div class="article-item">Most Read: Unrelated Story About Something Else</div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Team Wins Championship Final");
        assertThat(result).contains("dramatic victory in the final minutes");
        assertThat(result).contains("Fans celebrated wildly");
        assertThat(result).doesNotContain("Unrelated Story");
        assertThat(result).doesNotContain("Most Read");
    }

    @Test
    void returnsNullWhenNoArticleBodyPresent() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Weather Opinion</nav>
                <div class="article-item"><a href="/g/1">Team Wins Championship Final</a></div>
                <div class="article-item"><a href="/g/2">Another Unrelated Story</a></div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=VisirPageHandlerTest`
Expected: compile failure — `VisirPageHandler` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/extract/PageHandler.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;

public interface PageHandler {

    boolean matches(String url);

    String extract(Document doc);
}
```

Create `src/main/java/com/breynisson/router/extract/VisirPageHandler.java`:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class VisirPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("visir.is");
    }

    @Override
    public String extract(Document doc) {
        Element body = doc.selectFirst("div[itemprop=articleBody]");
        if (body == null) {
            return null;
        }
        Element headline = doc.selectFirst("h1");
        if (headline == null) {
            return body.text();
        }
        return headline.text() + "\n\n" + body.text();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=VisirPageHandlerTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandler.java src/main/java/com/breynisson/router/extract/VisirPageHandler.java src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add VisirPageHandler to extract clean article text from visir.is pages"
```

---

### Task 2: `PageHandlers` registry

**Files:**
- Create: `src/main/java/com/breynisson/router/extract/PageHandlers.java`
- Test: `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`

**Interfaces:**
- Consumes: `VisirPageHandler` (Task 1).
- Produces: `PageHandlers.find(String url) -> Optional<PageHandler>`, used by Task 3's `DefaultDigitalMeStorage.addContent()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/extract/PageHandlersTest.java`:

```java
package com.breynisson.router.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageHandlersTest {

    @Test
    void findsVisirHandlerForVisirUrl() {
        assertThat(PageHandlers.find("https://www.visir.is/g/123/some-article"))
                .containsInstanceOf(VisirPageHandler.class);
    }

    @Test
    void returnsEmptyForUnrelatedUrl() {
        assertThat(PageHandlers.find("https://www.example.com/page")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: compile failure — `PageHandlers` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/extract/PageHandlers.java`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=PageHandlersTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandlers.java src/test/java/com/breynisson/router/extract/PageHandlersTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add PageHandlers registry for per-site content extraction"
```

---

### Task 3: Wire `PageHandlers` into `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `PageHandlers.find(String url) -> Optional<PageHandler>` (Task 2), `PageHandler.extract(Document) -> String` (Task 1).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the same `request(...)`/`cleanupDb(...)` helpers already in the file):

```java
    @Test
    void addContentExtractsVisirArticleBody() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Weather Opinion Most Read: Some Other Story</nav>
                <article class="article-single -sport">
                    <header class="article-single__header">
                        <h1>Team Wins Championship Final</h1>
                    </header>
                    <div class="article-single__content">
                        <article>
                            <div itemprop="articleBody">
                                <p>The home team secured a dramatic victory in the final minutes.</p>
                            </div>
                        </article>
                    </div>
                </article>
                <div class="article-item">Most Read: Unrelated Story About Something Else</div>
                </body>
                </html>
                """;
        AddContentRequest req = request("https://www.visir.is/g/123/team-wins-championship", "Team Wins", html);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("dramatic").results().size());
        assertTrue(storage.search("Unrelated").results().isEmpty());

        cleanupDb("https://www.visir.is/g/123/team-wins-championship");
    }

    @Test
    void addContentDiscardsVisirFrontPage() {
        cleanupDb("https://www.visir.is");
        String html = """
                <html>
                <body>
                <nav>Home News Sports Weather Opinion</nav>
                <div class="article-item"><a href="/g/1">Team Wins Championship Final</a></div>
                <div class="article-item"><a href="/g/2">Another Unrelated Story</a></div>
                </body>
                </html>
                """;
        AddContentRequest req = request("https://www.visir.is", "Visir Front Page", html);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://www.visir.is").isEmpty());
        assertTrue(storage.search("Championship").results().isEmpty());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: both new tests FAIL — today's code runs the generic Jsoup `.text()` strip for both URLs, so `addContentExtractsVisirArticleBody` finds "Unrelated" in the indexed text (generic strip keeps nav/related content), and `addContentDiscardsVisirFrontPage` actually indexes the front page (so `TextEntryDao.findByName(...)` is non-empty and `search("Championship")` returns a result).

- [ ] **Step 3: Wire the registry into `addContent()` and extract the `normalize()` helper**

In `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`, the current method body reads:

```java
    @Override
    public AddContentResponse addContent(AddContentRequest addContentRequest) {
        lock.lock();
        AddContentResponse contentResponse = new AddContentResponse();
        try {
            log.info("addContent: {}", addContentRequest.getSource());
            String content = addContentRequest.getContent();
            if (addContentRequest.getSource().startsWith("http")) {
                if (ScreenshotCoverage.isCovered(addContentRequest.getSource())) {
                    log.info("Discarding extension content already covered by screenshot capture: {}", addContentRequest.getSource());
                    contentResponse.setSuccess(true);
                    return contentResponse;
                }
                if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
                    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
                } else {
                    content = Jsoup.parse(content).text();
                    content = content.replace("\\n", " ");
                    content = content.replace("\\t", " ");
                    content = content.replace("\\r", " ");
                    content = content.replaceAll("\\s+", " ").strip();
                }
                addContentRequest.setContent(content);
            }
            Path written = resourceReceiver.addContent(addContentRequest);
            CompletableFuture.runAsync(() -> embeddingIndex.indexFile(written));
            LuceneIndex.createOrUpdateIndex(content, addContentRequest.getSource(), addContentRequest.getName());
            TextEntryDao.insertOrUpdate(addContentRequest.getSource());
            contentResponse.setSuccess(true);
        } catch (Exception e) {
            log.error("Error in addContent for {}", addContentRequest.getSource(), e);
            contentResponse.setSuccess(false);
            contentResponse.setErrorMessage(e.getMessage());
        } finally {
            lock.unlock();
        }
        return contentResponse;
    }
```

Replace the whole class body with:

```java
package com.breynisson.router.digitalme;

import com.breynisson.router.extract.PageHandler;
import com.breynisson.router.extract.PageHandlers;
import com.breynisson.router.extract.YouTubeCaptionExtractor;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultDigitalMeStorage implements DigitalMeStorage {

    private static final Logger log = LoggerFactory.getLogger(DefaultDigitalMeStorage.class);

    private final Lock lock = new ReentrantLock();
    private final ResourceReceiver resourceReceiver;
    private final EmbeddingIndex embeddingIndex;

    public DefaultDigitalMeStorage(String dataDir, EmbeddingIndex embeddingIndex) {
        this.resourceReceiver = new ResourceReceiver(dataDir);
        this.embeddingIndex = embeddingIndex;
    }

    @Override
    public SearchResponse search(String keywords) {
        log.info("Search: {}", keywords);
        List<SearchResult> results = LuceneIndex.find(keywords);
        return new SearchResponse(new LinkedHashSet<>(results));
    }

    @Override
    public AddContentResponse addContent(AddContentRequest addContentRequest) {
        lock.lock();
        AddContentResponse contentResponse = new AddContentResponse();
        try {
            log.info("addContent: {}", addContentRequest.getSource());
            String content = addContentRequest.getContent();
            if (addContentRequest.getSource().startsWith("http")) {
                if (ScreenshotCoverage.isCovered(addContentRequest.getSource())) {
                    log.info("Discarding extension content already covered by screenshot capture: {}", addContentRequest.getSource());
                    contentResponse.setSuccess(true);
                    return contentResponse;
                }
                Optional<PageHandler> handler = PageHandlers.find(addContentRequest.getSource());
                if (handler.isPresent()) {
                    String extracted = handler.get().extract(Jsoup.parse(content));
                    if (extracted == null) {
                        log.info("Discarding content with no extractable body: {}", addContentRequest.getSource());
                        contentResponse.setSuccess(true);
                        return contentResponse;
                    }
                    content = normalize(extracted);
                } else if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
                    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
                } else {
                    content = normalize(Jsoup.parse(content).text());
                }
                addContentRequest.setContent(content);
            }
            Path written = resourceReceiver.addContent(addContentRequest);
            CompletableFuture.runAsync(() -> embeddingIndex.indexFile(written));
            LuceneIndex.createOrUpdateIndex(content, addContentRequest.getSource(), addContentRequest.getName());
            TextEntryDao.insertOrUpdate(addContentRequest.getSource());
            contentResponse.setSuccess(true);
        } catch (Exception e) {
            log.error("Error in addContent for {}", addContentRequest.getSource(), e);
            contentResponse.setSuccess(false);
            contentResponse.setErrorMessage(e.getMessage());
        } finally {
            lock.unlock();
        }
        return contentResponse;
    }

    private static String normalize(String content) {
        String normalized = content.replace("\\n", " ");
        normalized = normalized.replace("\\t", " ");
        normalized = normalized.replace("\\r", " ");
        return normalized.replaceAll("\\s+", " ").strip();
    }
}
```

The early `return contentResponse;` in both the `ScreenshotCoverage` and the new handler-discard branch are inside the existing `try` block, so `finally { lock.unlock(); }` still runs in both cases.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: all tests in the class pass, including both new ones.

- [ ] **Step 5: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions in other test classes (e.g. `addContentStripsHtmlForHttpSources`, which exercises the generic non-visir path and must still pass since `normalize()` is behaviorally identical to the inline code it replaced).

- [ ] **Step 6: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: use VisirPageHandler for visir.is content, discard non-article pages"
```

---

### Task 4: Document the new extraction mechanism

**Files:**
- Modify: `docs/architecture.md:14`
- Modify: `docs/architecture.md` (new subsystem section after `YouTubeCaptionExtractor`, currently ending at line 118)

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `/addContent` description**

In `docs/architecture.md`, line 14 currently reads:

```markdown
`/addContent` uses a `ReentrantLock` for thread safety. If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing — unless `ScreenshotCoverage.isCovered()` determines the URL is a LinkedIn/Facebook/Quora page already captured more completely by the screenshot OCR pipeline, in which case the submission is silently discarded (still returns success, nothing is written or indexed).
```

Change to:

```markdown
`/addContent` uses a `ReentrantLock` for thread safety. If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing — unless `ScreenshotCoverage.isCovered()` determines the URL is a LinkedIn/Facebook/Quora page already captured more completely by the screenshot OCR pipeline, in which case the submission is silently discarded (still returns success, nothing is written or indexed). If a `PageHandler` in the `PageHandlers` registry matches the URL (e.g. `VisirPageHandler` for visir.is), its `extract()` result is used instead of the generic Jsoup strip; if that handler returns no extractable content, the submission is silently discarded the same way.
```

- [ ] **Step 2: Add a `PageHandler` / `VisirPageHandler` / `PageHandlers` subsystem note**

In `docs/architecture.md`, the `YouTubeCaptionExtractor` section currently reads (ending right before the `---` divider before "Database schema"):

```markdown
### `YouTubeCaptionExtractor`
- Located in `extract/` package
- `extractFromYouTubeUrl(url)`: parses `v=` query param, calls `extract(videoId)`
- `extract(videoId)`: uses `youtube-transcript-api` library; returns timed transcript lines as `[start_sec] text\n`

---
```

Add a new subsystem section directly after it, before the `---`:

```markdown
### `PageHandler` / `PageHandlers` / `VisirPageHandler`
- Located in `extract/` package, alongside `YouTubeCaptionExtractor`
- `PageHandler` interface: `matches(url)` decides if a handler applies; `extract(Document)` returns the clean extracted text, or `null` to signal the submission has nothing worth indexing (discarded the same way as a `ScreenshotCoverage` match)
- `PageHandlers.find(url)` — static registry; returns the first matching handler, or empty if none apply (falls through to the generic Jsoup strip / YouTube extraction)
- `VisirPageHandler` — matches any `visir.is` URL; extracts the `h1` headline plus `div[itemprop=articleBody]` text, skipping all nav/related-article/footer markup. Returns `null` when no `articleBody` element is present, which covers the front page and other non-article pages (section fronts, live-blog hubs) without a separate root-URL check
- To add a new site: implement `PageHandler` and add it to `PageHandlers`'s `HANDLERS` list — no other code changes needed
```

- [ ] **Step 3: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document PageHandler extraction mechanism and VisirPageHandler"
```

---

### Task 5: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no violations (matches `docs/tooling.md`'s rules — unused imports, equals-avoid-null, etc.).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/special_handler_visir --stat`
Expected: shows changes to `src/main/java/com/breynisson/router/extract/PageHandler.java` (new), `src/main/java/com/breynisson/router/extract/VisirPageHandler.java` (new), `src/main/java/com/breynisson/router/extract/PageHandlers.java` (new), `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`, `src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java` (new), `src/test/java/com/breynisson/router/extract/PageHandlersTest.java` (new), `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST the real visir.is article HTML (e.g. the sample already fetched during design at a scratch location, or any current visir.is `/g/...` article page) to `/addContent` directly and confirm via `/search?keywords=...` that a search for a word from the article body finds it, while a search for text only present in the site nav/related-articles list does not. Then POST the visir.is front page HTML and confirm nothing gets indexed (`TextEntryDao.findByName("https://www.visir.is")` stays empty).
