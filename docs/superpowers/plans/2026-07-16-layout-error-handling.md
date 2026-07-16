# Layout-Change Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a registered `PageHandler`'s expected content markers are missing from a page whose URL looks like an article (not just "legitimately not an article"), fall back to the generic Jsoup `.text()` extraction instead of discarding, and write a throttled (once per site per calendar month) alert file so the breakage is discoverable.

**Architecture:** `PageHandler` gains two new methods — `looksLikeArticleUrl(url)` (a cheap per-handler URL-shape heuristic, distinct from `matches(url)`) and `siteName()` (a short filename-safe slug). A new standalone class, `LayoutChangeReporter`, owns the throttled file-writing (mirrors `ResourceReceiver`'s `dataDir`-based construction). `DefaultDigitalMeStorage.addContent()` is the only wiring point: when a matched handler's `extract()` returns `null`, it now checks `looksLikeArticleUrl()` to decide between the existing discard path (URL doesn't look like an article — unchanged behavior) and a new fallback-plus-report path (URL looks like an article — index via generic Jsoup, report once per month).

**Tech Stack:** Java 19, Jsoup (existing dependency), JUnit 5, AssertJ, Jackson `ObjectMapper` (existing project stack — no new dependencies).

## Global Constraints

- `looksLikeArticleUrl(url)` is consulted **only** when `extract()` has already returned `null` — it must not change any other code path.
- The existing "discard the front page" behavior for all three handlers must be **completely unchanged** — verified by the existing `addContentDiscardsVisirFrontPage`, `addContentDiscardsDVFrontPage`, `addContentDiscardsFotboltiFrontPage` tests continuing to pass without modification, since none of the three front-page URLs already used in those tests match any handler's new `looksLikeArticleUrl()` pattern.
- Per-handler `looksLikeArticleUrl()` patterns (based on each site's real, already-observed article URL shape):
  - `VisirPageHandler`: `url.contains("/g/")`
  - `DVPageHandler`: path matches the regex `/\d+/\d{4}/\d{2}/\d{2}/`
  - `FotboltiPageHandler`: `url.contains("/news/")`
- `siteName()` values: `VisirPageHandler` → `"visir"`, `DVPageHandler` → `"dv"`, `FotboltiPageHandler` → `"fotbolti"`.
- `LayoutChangeReporter` writes to `<dataDir>/errors/`, filename `<year>-<month>-<day>-<hour>-<minute>-<second>-<siteName>.txt` (each numeric field zero-padded to 2 digits except year, which is 4 digits), file content is exactly the message string passed in.
- Throttling: before writing, `LayoutChangeReporter` checks whether any file already exists in `errors/` matching `<year>-<month>-*-<siteName>.txt` for the *current* calendar month — if so, `report()` is a no-op. This check is filesystem-based (not in-memory), so it survives app restarts.
- The layout-changed fallback message format (built in `DefaultDigitalMeStorage`, not `LayoutChangeReporter`, which only knows about file-writing): `"<scheme>://<host> has changed the layout, so <HandlerSimpleClassName> can't find the main content. Falling back to default jsoup handling."` — e.g. `"https://www.visir.is has changed the layout, so VisirPageHandler can't find the main content. Falling back to default jsoup handling."`
- No new synchronization needed in `LayoutChangeReporter` — its only caller, `DefaultDigitalMeStorage.addContent()`, already holds a `ReentrantLock` around its whole body.
- Any test exercising the full `DefaultDigitalMeStorage.addContent()` pipeline must build its HTML fixture via `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html)`, per the established project convention (real Chrome-extension payload shape, not clean HTML).
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `LayoutChangeReporter`

**Files:**
- Create: `src/main/java/com/breynisson/router/digitalme/LayoutChangeReporter.java`
- Test: `src/test/java/com/breynisson/router/digitalme/LayoutChangeReporterTest.java`

**Interfaces:**
- Produces: `LayoutChangeReporter(String dataDir)` constructor; `void report(String siteName, String message)`. Used by Task 3's `DefaultDigitalMeStorage`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/digitalme/LayoutChangeReporterTest.java`:

```java
package com.breynisson.router.digitalme;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutChangeReporterTest {

    @TempDir
    Path dataDir;

    private LayoutChangeReporter reporter;

    @BeforeEach
    void setUp() {
        reporter = new LayoutChangeReporter(dataDir.toString());
    }

    private List<Path> errorFiles() throws IOException {
        Path errorsDir = dataDir.resolve("errors");
        try (Stream<Path> stream = Files.list(errorsDir)) {
            return stream.toList();
        }
    }

    @Test
    void firstReportWritesFileWithExpectedNameAndContent() throws IOException {
        reporter.report("testsite", "some layout-change message");

        List<Path> files = errorFiles();
        assertEquals(1, files.size());
        String fileName = files.get(0).getFileName().toString();
        assertTrue(fileName.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-testsite\\.txt"));
        assertEquals("some layout-change message", Files.readString(files.get(0)));
    }

    @Test
    void secondReportForSameSiteSameMonthIsNoOp() throws IOException {
        reporter.report("testsite", "first message");
        reporter.report("testsite", "second message");

        List<Path> files = errorFiles();
        assertEquals(1, files.size());
        assertEquals("first message", Files.readString(files.get(0)));
    }

    @Test
    void reportsForDifferentSitesBothWriteFiles() throws IOException {
        reporter.report("siteone", "message one");
        reporter.report("sitetwo", "message two");

        List<Path> files = errorFiles();
        assertEquals(2, files.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=LayoutChangeReporterTest`
Expected: compile failure — `LayoutChangeReporter` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/digitalme/LayoutChangeReporter.java`:

```java
package com.breynisson.router.digitalme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.stream.Stream;

public class LayoutChangeReporter {

    private static final Logger log = LoggerFactory.getLogger(LayoutChangeReporter.class);

    private final Path errorsDir;

    public LayoutChangeReporter(String dataDir) {
        this.errorsDir = Paths.get(dataDir, "errors");
    }

    public void report(String siteName, String message) {
        try {
            Files.createDirectories(errorsDir);
            if (alreadyReportedThisMonth(siteName)) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            String fileName = String.format("%04d-%02d-%02d-%02d-%02d-%02d-%s.txt",
                    now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                    now.getHour(), now.getMinute(), now.getSecond(), siteName);
            Files.writeString(errorsDir.resolve(fileName), message);
        } catch (IOException e) {
            log.error("Error writing layout-change report for {}", siteName, e);
        }
    }

    private boolean alreadyReportedThisMonth(String siteName) throws IOException {
        YearMonth currentMonth = YearMonth.now();
        String prefix = String.format("%04d-%02d-", currentMonth.getYear(), currentMonth.getMonthValue());
        String suffix = "-" + siteName + ".txt";
        try (Stream<Path> files = Files.list(errorsDir)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(suffix);
            });
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=LayoutChangeReporterTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/digitalme/LayoutChangeReporter.java src/test/java/com/breynisson/router/digitalme/LayoutChangeReporterTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add LayoutChangeReporter for throttled per-site-per-month alert files"
```

---

### Task 2: `PageHandler` interface + all three handlers gain `looksLikeArticleUrl()` and `siteName()`

**Files:**
- Modify: `src/main/java/com/breynisson/router/extract/PageHandler.java`
- Modify: `src/main/java/com/breynisson/router/extract/VisirPageHandler.java`
- Modify: `src/main/java/com/breynisson/router/extract/DVPageHandler.java`
- Modify: `src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java`
- Modify: `src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java`
- Modify: `src/test/java/com/breynisson/router/extract/DVPageHandlerTest.java`
- Modify: `src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java`

**Interfaces:**
- Produces: `PageHandler.looksLikeArticleUrl(String url) -> boolean` and `PageHandler.siteName() -> String`, implemented by all three handlers. Used by Task 3's `DefaultDigitalMeStorage`.

This is one task because Java requires every class implementing an interface to satisfy its full method set for the project to compile — splitting the interface change from its three implementers across separate tasks would leave the build broken in between.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://www.visir.is/g/20262909759d/messi-allt-i-ollu")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.visir.is")).isFalse();
    }

    @Test
    void siteNameIsVisir() {
        assertThat(handler.siteName()).isEqualTo("visir");
    }
```

Add to `src/test/java/com/breynisson/router/extract/DVPageHandlerTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://www.dv.is/433/2026/07/15/storstjarna-faer-a-baukinn")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.dv.is")).isFalse();
    }

    @Test
    void siteNameIsDv() {
        assertThat(handler.siteName()).isEqualTo("dv");
    }
```

Add to `src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java` (alongside the existing `@Test` methods):

```java
    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://fotbolti.net/news/16-07-2026/otrulegir-yfirburdur-argentinu")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.fotbolti.net")).isFalse();
    }

    @Test
    void siteNameIsFotbolti() {
        assertThat(handler.siteName()).isEqualTo("fotbolti");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=VisirPageHandlerTest,DVPageHandlerTest,FotboltiPageHandlerTest`
Expected: compile failure — `looksLikeArticleUrl`/`siteName` don't exist on `PageHandler` yet.

- [ ] **Step 3: Update the interface and all three implementations**

Replace `src/main/java/com/breynisson/router/extract/PageHandler.java` with:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;

public interface PageHandler {

    boolean matches(String url);

    String extract(Document doc);

    boolean looksLikeArticleUrl(String url);

    String siteName();
}
```

Replace `src/main/java/com/breynisson/router/extract/VisirPageHandler.java` with:

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

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return url.contains("/g/");
    }

    @Override
    public String siteName() {
        return "visir";
    }
}
```

Replace `src/main/java/com/breynisson/router/extract/DVPageHandler.java` with:

```java
package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Pattern;

public class DVPageHandler implements PageHandler {

    private static final Pattern ARTICLE_URL_PATTERN = Pattern.compile("/\\d+/\\d{4}/\\d{2}/\\d{2}/");

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

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return ARTICLE_URL_PATTERN.matcher(url).find();
    }

    @Override
    public String siteName() {
        return "dv";
    }
}
```

Replace `src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java` with:

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

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return url.contains("/news/");
    }

    @Override
    public String siteName() {
        return "fotbolti";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=VisirPageHandlerTest,DVPageHandlerTest,FotboltiPageHandlerTest`
Expected: `VisirPageHandlerTest` 7/7, `DVPageHandlerTest` 7/7, `FotboltiPageHandlerTest` 7/7 (4 pre-existing + 3 new each), all passing.

- [ ] **Step 5: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions (in particular `PageHandlersTest`, which resolves handler instances but doesn't call the new methods, should be unaffected).

- [ ] **Step 6: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/extract/PageHandler.java src/main/java/com/breynisson/router/extract/VisirPageHandler.java src/main/java/com/breynisson/router/extract/DVPageHandler.java src/main/java/com/breynisson/router/extract/FotboltiPageHandler.java src/test/java/com/breynisson/router/extract/VisirPageHandlerTest.java src/test/java/com/breynisson/router/extract/DVPageHandlerTest.java src/test/java/com/breynisson/router/extract/FotboltiPageHandlerTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add looksLikeArticleUrl and siteName to PageHandler and its implementations"
```

---

### Task 3: Wire `LayoutChangeReporter` into `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `LayoutChangeReporter` (Task 1), `PageHandler.looksLikeArticleUrl()`/`siteName()` (Task 2).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the file's existing `request(...)`/`cleanupDb(...)` helpers). This single test covers both the fallback-and-report behavior and the once-per-month throttle, since `dataDir` is a `static @TempDir` shared across the whole test class — structuring it as one test with two sequential `addContent()` calls avoids any dependency on JUnit's unspecified inter-method execution order:

```java
    @Test
    void addContentFallsBackAndReportsOnceWhenVisirLayoutChanges() throws com.fasterxml.jackson.core.JsonProcessingException {
        String firstHtml = """
                <html>
                <body>
                <nav>Home News Sports Weather Opinion</nav>
                <h1>Team Wins Championship Final</h1>
                <div class="some-new-layout">The home team secured a dramatic victory in the final minutes.</div>
                </body>
                </html>
                """;
        String firstPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(firstHtml);
        AddContentRequest firstReq = request("https://www.visir.is/g/111/team-wins-championship", "Team Wins", firstPayload);

        AddContentResponse firstResponse = storage.addContent(firstReq);

        assertTrue(firstResponse.isSuccess());
        assertEquals(1, storage.search("dramatic").results().size());

        java.nio.file.Path errorsDir = dataDir.resolve("errors");
        java.util.List<java.nio.file.Path> filesAfterFirst;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(errorsDir)) {
            filesAfterFirst = stream.toList();
        }
        assertEquals(1, filesAfterFirst.size());
        String reportContent = java.nio.file.Files.readString(filesAfterFirst.get(0));
        assertTrue(reportContent.contains("https://www.visir.is"));
        assertTrue(reportContent.contains("VisirPageHandler"));
        assertTrue(reportContent.contains("Falling back to default jsoup handling"));

        String secondHtml = """
                <html>
                <body>
                <nav>Home News Sports Weather Opinion</nav>
                <h1>Another Story With Changed Layout</h1>
                <div class="some-new-layout">The visiting team staged a spectacular comeback in the second half.</div>
                </body>
                </html>
                """;
        String secondPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(secondHtml);
        AddContentRequest secondReq = request("https://www.visir.is/g/222/another-story-with-changed-layout", "Another Story", secondPayload);

        AddContentResponse secondResponse = storage.addContent(secondReq);

        assertTrue(secondResponse.isSuccess());
        assertEquals(1, storage.search("spectacular").results().size());

        java.util.List<java.nio.file.Path> filesAfterSecond;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(errorsDir)) {
            filesAfterSecond = stream.toList();
        }
        assertEquals(1, filesAfterSecond.size());

        cleanupDb("https://www.visir.is/g/111/team-wins-championship");
        cleanupDb("https://www.visir.is/g/222/another-story-with-changed-layout");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: `addContentFallsBackAndReportsOnceWhenVisirLayoutChanges` FAILS — today's code, with no `looksLikeArticleUrl()` check, treats `extracted == null` as an unconditional discard, so nothing gets indexed (`storage.search("dramatic")` returns 0 results) and no `errors/` directory is ever created (the `Files.list(errorsDir)` call throws `NoSuchFileException`).

- [ ] **Step 3: Wire the fallback-and-report logic into `addContent()`**

Replace `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java` with this exact content:

```java
package com.breynisson.router.digitalme;

import com.breynisson.router.extract.PageHandler;
import com.breynisson.router.extract.PageHandlers;
import com.breynisson.router.extract.YouTubeCaptionExtractor;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultDigitalMeStorage implements DigitalMeStorage {

    private static final Logger log = LoggerFactory.getLogger(DefaultDigitalMeStorage.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Lock lock = new ReentrantLock();
    private final ResourceReceiver resourceReceiver;
    private final EmbeddingIndex embeddingIndex;
    private final LayoutChangeReporter layoutChangeReporter;

    public DefaultDigitalMeStorage(String dataDir, EmbeddingIndex embeddingIndex) {
        this.resourceReceiver = new ResourceReceiver(dataDir);
        this.embeddingIndex = embeddingIndex;
        this.layoutChangeReporter = new LayoutChangeReporter(dataDir);
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
                    return discard(contentResponse, "Discarding extension content already covered by screenshot capture", addContentRequest.getSource());
                }
                Optional<PageHandler> handler = PageHandlers.find(addContentRequest.getSource());
                if (handler.isPresent()) {
                    PageHandler pageHandler = handler.get();
                    String decoded = decodeIfJsonEncoded(content);
                    String extracted = pageHandler.extract(Jsoup.parse(decoded));
                    if (extracted != null) {
                        content = normalize(extracted);
                    } else if (pageHandler.looksLikeArticleUrl(addContentRequest.getSource())) {
                        reportLayoutChange(pageHandler, addContentRequest.getSource());
                        content = normalize(Jsoup.parse(decoded).text());
                    } else {
                        return discard(contentResponse, "Discarding content with no extractable body", addContentRequest.getSource());
                    }
                } else if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
                    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
                } else {
                    content = normalize(Jsoup.parse(decodeIfJsonEncoded(content)).text());
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

    private void reportLayoutChange(PageHandler pageHandler, String source) {
        String domain = extractDomain(source);
        String message = String.format(
                "%s has changed the layout, so %s can't find the main content. Falling back to default jsoup handling.",
                domain, pageHandler.getClass().getSimpleName());
        layoutChangeReporter.report(pageHandler.siteName(), message);
    }

    private static String extractDomain(String source) {
        try {
            URI uri = URI.create(source);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return source;
        }
    }

    private static AddContentResponse discard(AddContentResponse contentResponse, String reason, String source) {
        log.info("{}: {}", reason, source);
        contentResponse.setSuccess(true);
        return contentResponse;
    }

    // The Chrome extension double-JSON-encodes page content: content-script.js sends
    // JSON.stringify(document.body.innerHTML), then background.js wraps the whole
    // request in JSON.stringify(request) again. Jackson decodes only the outer
    // envelope, so getContent() is still a JSON-quoted string, not real HTML --
    // decode that one remaining layer before any HTML parsing happens. Content that
    // isn't JSON-string-shaped (e.g. plain HTML from a future/non-extension producer)
    // passes through unchanged.
    private static String decodeIfJsonEncoded(String content) {
        if (content == null || content.isEmpty() || content.charAt(0) != '"') {
            return content;
        }
        try {
            return OBJECT_MAPPER.readValue(content, String.class);
        } catch (JsonProcessingException e) {
            return content;
        }
    }

    private static String normalize(String content) {
        String normalized = content.replace("\\n", " ");
        normalized = normalized.replace("\\t", " ");
        normalized = normalized.replace("\\r", " ");
        return normalized.replaceAll("\\s+", " ").strip();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: all tests in the class pass, including the new one and every pre-existing one — in particular `addContentDiscardsVisirFrontPage`, `addContentDiscardsDVFrontPage`, and `addContentDiscardsFotboltiFrontPage` must still pass completely unmodified, proving the front-page-discard behavior survived this change (none of their URLs match any handler's `looksLikeArticleUrl()` pattern, so they still hit the `discard(...)` branch).

- [ ] **Step 5: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: fall back to generic Jsoup and report once per month when a handler's layout is gone"
```

---

### Task 4: Document the layout-change fallback mechanism

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `/addContent` description**

In `docs/architecture.md`, find the `/addContent` paragraph (it currently ends with a sentence about `decodeIfJsonEncoded()`). Add one more sentence at the end of that paragraph:

```markdown
 If a matched handler's `extract()` returns `null` but the URL looks like an article (per that handler's `looksLikeArticleUrl()`), the submission falls back to the generic Jsoup strip instead of being discarded, and `LayoutChangeReporter` writes a one-per-site-per-month alert file to `<dataDir>/errors/` — see the `PageHandler` subsystem note below.
```

- [ ] **Step 2: Update the `PageHandler` subsystem section**

In `docs/architecture.md`, find the section documenting `PageHandler`/`PageHandlers`/`VisirPageHandler`/`DVPageHandler`/`FotboltiPageHandler`. Add two new bullets at the end (before the "To add a new site" bullet):

```markdown
- `looksLikeArticleUrl(url)` — cheap per-handler URL-shape heuristic (e.g. `VisirPageHandler` checks for `/g/`, `DVPageHandler` for a `/<id>/<yyyy>/<mm>/<dd>/` path, `FotboltiPageHandler` for `/news/`), consulted only when `extract()` returns `null`, to distinguish "legitimately not an article" (discard, unchanged) from "should be an article but the layout changed" (fall back + report)
- `siteName()` — short filename-safe slug (`visir`/`dv`/`fotbolti`) used by `LayoutChangeReporter`
```

Then add a new subsystem entry right after that section, before the next `---` divider:

```markdown
### `LayoutChangeReporter`
- Located in `digitalme/` package, alongside `DefaultDigitalMeStorage`
- `report(siteName, message)` writes `<dataDir>/errors/<year>-<month>-<day>-<hour>-<minute>-<second>-<siteName>.txt` with `message` as the file's content
- Throttled to once per site per calendar month: before writing, checks whether a file already exists in `errors/` matching `<year>-<month>-*-<siteName>.txt`; if so, silently no-ops. The check is filesystem-based, so it survives app restarts
- Called from `DefaultDigitalMeStorage.addContent()` when a matched handler's `extract()` returns `null` for a URL that `looksLikeArticleUrl()`
```

- [ ] **Step 3: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document the layout-change fallback and LayoutChangeReporter"
```

---

### Task 5: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no new violations (the project has 9 pre-existing violations in files this branch doesn't touch — `IndexPage.java`, `TranscriptListExtractor.java`, `FileChangeWatcherTest.java` — confirm via `git diff main...feature/layout_error_handling --stat` on those exact paths showing an empty diff).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/layout_error_handling --stat`
Expected: shows changes to `docs/architecture.md`, `LayoutChangeReporter.java` (new) + test (new), `PageHandler.java`, `VisirPageHandler.java`, `DVPageHandler.java`, `FotboltiPageHandler.java` + their three test files, `DefaultDigitalMeStorage.java` + test, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Explicitly confirm the three existing front-page-discard tests are unmodified**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/layout_error_handling -- src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`
Expected: the diff shows only additions (the new `addContentFallsBackAndReportsOnceWhenVisirLayoutChanges` test and its imports/helpers) — no existing test method's body is touched, confirming `addContentDiscardsVisirFrontPage`, `addContentDiscardsDVFrontPage`, and `addContentDiscardsFotboltiFrontPage` are byte-for-byte unchanged.

- [ ] **Step 5: Manual sanity check (optional but recommended)**

With the app running, POST HTML for a real visir.is/dv.is/fotbolti.net article URL but with the `div`/class the handler expects deliberately removed (e.g. edit a saved copy) to `/addContent`, and confirm: (a) the content is still searchable afterward (fallback indexing worked), and (b) a new file appears under `<dataDir>/errors/` with the expected name and message. POST a second, different broken article for the same site and confirm no second error file appears.
