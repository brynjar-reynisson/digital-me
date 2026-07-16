# Markdown Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render `.md`/`.markdown` files as formatted HTML when viewed through `GET /localFile`, instead of showing raw markdown source as escaped plain text.

**Architecture:** A new static utility class, `MarkdownPageRenderer`, wraps `commonmark-java` (with GFM tables/strikethrough/task-list extensions) to convert markdown to a self-contained HTML page. `IndexPage.localFile()` calls it for markdown files and falls through to its existing escape-and-wrap logic for everything else.

**Tech Stack:** Java 19, Spring Boot 3.3.11 (`@RestController`), commonmark-java 0.29.0, JUnit 5, AssertJ.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-16-markdown-display-design.md` — read it before starting if anything below is ambiguous.
- Build with the IntelliJ-bundled Maven (not on PATH): `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" <goals>`.
- Checkstyle (`checkstyle.xml`) enforces: no unused imports, no `sun.*`/`com.sun.*` imports (except `com.sun.net.httpserver` in tests), no empty catch blocks, no `==` string comparison, `.equals()` never called with a null literal first, no switch fallthrough, one statement per line, no multi-variable declarations, no needlessly complex boolean expressions/returns. Run `mvn checkstyle:check` (via the alias above) after each change, or rely on the `.claude/settings.json` `PostToolUse` hook, which runs it automatically after every `Edit`/`Write` to a `.java` file.
- New markdown page output must be fully self-contained: inline `<style>` only, no external stylesheet/CDN/script.
- commonmark-java's `HtmlRenderer` defaults `escapeHtml` to `false` (raw HTML in markdown source passes through unescaped, per the CommonMark spec). This plan always builds the renderer with `.escapeHtml(true)` explicitly — do not drop this.
- New test files in `extract`/recently-touched packages use AssertJ (`assertThat(...)`, static import `org.assertj.core.api.Assertions.assertThat`) — follow that for the new `MarkdownPageRendererTest`. `IndexPageTest` already uses plain JUnit `Assertions.*` (`assertTrue`/`assertEquals`) — match that existing file's convention when extending it.
- Per this repo's CLAUDE.md: run `/simplify` after changing source files, and `git add` any newly created source file.

---

### Task 1: `MarkdownPageRenderer` — markdown-to-HTML conversion

**Files:**
- Modify: `pom.xml` (add commonmark-java dependencies)
- Create: `src/main/java/com/breynisson/router/ui/MarkdownPageRenderer.java`
- Test: `src/test/java/com/breynisson/router/ui/MarkdownPageRendererTest.java`

**Interfaces:**
- Produces: `MarkdownPageRenderer.isMarkdownFile(String filePath) -> boolean` and `MarkdownPageRenderer.render(String markdownContent) -> String` (a complete `<html>...</html>` document as a string) — both `public static`, consumed by Task 2.

- [ ] **Step 1: Add commonmark-java dependencies to `pom.xml`**

Open `pom.xml` and add these four dependencies inside the existing `<dependencies>` block, directly after the `pdfbox` dependency (around line 147, before the closing `</dependencies>`):

```xml
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark</artifactId>
            <version>0.29.0</version>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-gfm-tables</artifactId>
            <version>0.29.0</version>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-gfm-strikethrough</artifactId>
            <version>0.29.0</version>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-task-list-items</artifactId>
            <version>0.29.0</version>
        </dependency>
```

- [ ] **Step 2: Verify the dependencies resolve**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q dependency:resolve
```
Expected: completes with no errors (exit code 0). If a version can't be resolved, check https://central.sonatype.com/artifact/org.commonmark/commonmark/versions for the current release and adjust the version in all four dependencies to match (they're released together and share a version number).

- [ ] **Step 3: Commit the dependency addition**

```bash
git add pom.xml
git commit -m "build: add commonmark-java for markdown rendering"
```

- [ ] **Step 4: Write the failing tests for `MarkdownPageRenderer`**

Create `src/test/java/com/breynisson/router/ui/MarkdownPageRendererTest.java`:

```java
package com.breynisson.router.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownPageRendererTest {

    @Test
    void recognizesMdExtension() {
        assertThat(MarkdownPageRenderer.isMarkdownFile("C:/notes/example.md")).isTrue();
    }

    @Test
    void recognizesMdExtensionCaseInsensitively() {
        assertThat(MarkdownPageRenderer.isMarkdownFile("C:/notes/EXAMPLE.MD")).isTrue();
    }

    @Test
    void recognizesMarkdownExtension() {
        assertThat(MarkdownPageRenderer.isMarkdownFile("C:/notes/example.markdown")).isTrue();
    }

    @Test
    void doesNotRecognizeTxtExtension() {
        assertThat(MarkdownPageRenderer.isMarkdownFile("C:/notes/example.txt")).isFalse();
    }

    @Test
    void doesNotRecognizeFileWithNoExtension() {
        assertThat(MarkdownPageRenderer.isMarkdownFile("C:/notes/example")).isFalse();
    }

    @Test
    void doesNotRecognizeMdSubstringMidName() {
        assertThat(MarkdownPageRenderer.isMarkdownFile("C:/notes/example.md.txt")).isFalse();
    }

    @Test
    void rendersHeadingAsHtml() {
        String html = MarkdownPageRenderer.render("# Title");

        assertThat(html).contains("<h1>Title</h1>");
    }

    @Test
    void rendersTableAsHtml() {
        String markdown = """
                | A | B |
                | - | - |
                | 1 | 2 |
                """;

        String html = MarkdownPageRenderer.render(markdown);

        assertThat(html).contains("<table>");
        assertThat(html).contains("<td>1</td>");
    }

    @Test
    void rendersStrikethroughAsHtml() {
        String html = MarkdownPageRenderer.render("~~gone~~");

        assertThat(html).contains("<del>gone</del>");
    }

    @Test
    void rendersTaskListItemAsCheckbox() {
        String html = MarkdownPageRenderer.render("- [ ] todo item");

        assertThat(html).contains("type=\"checkbox\"");
    }

    @Test
    void escapesRawHtmlInSource() {
        String html = MarkdownPageRenderer.render("<script>alert(1)</script>");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail with a compile error**

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=MarkdownPageRendererTest
```
Expected: FAIL — compile error, `MarkdownPageRenderer` does not exist.

- [ ] **Step 6: Implement `MarkdownPageRenderer`**

Create `src/main/java/com/breynisson/router/ui/MarkdownPageRenderer.java`:

```java
package com.breynisson.router.ui;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

public final class MarkdownPageRenderer {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create()
    );

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .build();

    private static final String STYLE = """
            body {
                margin: 0;
                padding: 2rem 1rem;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                line-height: 1.6;
                color: #1a1a1a;
                background: #ffffff;
            }
            .markdown-body {
                max-width: 800px;
                margin: 0 auto;
            }
            .markdown-body pre {
                background: #f4f4f4;
                padding: 1rem;
                overflow-x: auto;
                border-radius: 4px;
            }
            .markdown-body code {
                background: #f4f4f4;
                padding: 0.15em 0.35em;
                border-radius: 3px;
                font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
            }
            .markdown-body pre code {
                background: none;
                padding: 0;
            }
            .markdown-body table {
                border-collapse: collapse;
                width: 100%;
            }
            .markdown-body th, .markdown-body td {
                border: 1px solid #d0d7de;
                padding: 0.5rem 0.75rem;
                text-align: left;
            }
            .markdown-body blockquote {
                margin: 0;
                padding-left: 1rem;
                border-left: 4px solid #d0d7de;
                color: #555555;
            }
            @media (prefers-color-scheme: dark) {
                body {
                    color: #e6e6e6;
                    background: #1a1a1a;
                }
                .markdown-body pre, .markdown-body code {
                    background: #2a2a2a;
                }
                .markdown-body th, .markdown-body td, .markdown-body blockquote {
                    border-color: #444444;
                }
                .markdown-body blockquote {
                    color: #aaaaaa;
                }
            }
            """;

    private MarkdownPageRenderer() {
    }

    public static boolean isMarkdownFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    public static String render(String markdownContent) {
        Node document = PARSER.parse(markdownContent);
        String bodyHtml = RENDERER.render(document);
        return "<html><head><meta charset=\"UTF-8\"><style>" + STYLE + "</style></head>"
                + "<body><div class=\"markdown-body\">" + bodyHtml + "</div></body></html>";
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=MarkdownPageRendererTest
```
Expected: PASS, all 10 tests green.

- [ ] **Step 8: Run checkstyle**

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q checkstyle:check
```
Expected: exit code 0, no violations printed.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/breynisson/router/ui/MarkdownPageRenderer.java src/test/java/com/breynisson/router/ui/MarkdownPageRendererTest.java
git commit -m "feat: add MarkdownPageRenderer for markdown-to-HTML conversion"
```

---

### Task 2: Wire `MarkdownPageRenderer` into `/localFile`

**Files:**
- Modify: `src/main/java/com/breynisson/router/ui/IndexPage.java:77-84`
- Test: `src/test/java/com/breynisson/router/ui/IndexPageTest.java`

**Interfaces:**
- Consumes: `MarkdownPageRenderer.isMarkdownFile(String) -> boolean`, `MarkdownPageRenderer.render(String) -> String` (from Task 1, same package — no import needed).

- [ ] **Step 1: Write the failing tests in `IndexPageTest`**

Open `src/test/java/com/breynisson/router/ui/IndexPageTest.java` and update the imports at the top to:

```java
package com.breynisson.router.ui;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.digitalme.AddContentResponse;
import com.breynisson.router.digitalme.SearchResponse;
import com.breynisson.router.digitalme.TestDigitalMeStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
```

Add a `@TempDir` field alongside the existing `storage`/`indexPage` fields:

```java
    private TestDigitalMeStorage storage;
    private IndexPage indexPage;

    @TempDir
    Path tempDir;
```

Add these two test methods to the class (after `addContentDelegatesToStorage`, before the private `request` helper):

```java
    @Test
    void localFileRendersMarkdownAsHtml() throws IOException {
        Path mdFile = tempDir.resolve("notes.md");
        Files.writeString(mdFile, "# Hello World");

        String html = indexPage.localFile(mdFile.toString());

        assertTrue(html.contains("<h1>Hello World</h1>"));
    }

    @Test
    void localFileEscapesPlainTextFile() throws IOException {
        Path txtFile = tempDir.resolve("notes.txt");
        Files.writeString(txtFile, "# Not a heading");

        String html = indexPage.localFile(txtFile.toString());

        assertTrue(html.contains("# Not a heading"));
        assertFalse(html.contains("<h1>"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=IndexPageTest
```
Expected: FAIL — `localFileRendersMarkdownAsHtml` fails because `<h1>Hello World</h1>` is not in the current escaped-plain-text output (`localFileEscapesPlainTextFile` already passes against the current implementation, since that behavior is unchanged — that's expected and fine).

- [ ] **Step 3: Update `IndexPage.localFile()`**

In `src/main/java/com/breynisson/router/ui/IndexPage.java`, add this import alongside the existing `org.springframework.web.util.HtmlUtils` import:

```java
import org.springframework.http.MediaType;
```

Replace the `localFile` method (lines 77-84):

```java
    @GetMapping("/localFile")
    public String localFile(@RequestParam String filePath) throws IOException {
        String content = Files.readString(Paths.get(filePath));
        content = HtmlUtils.htmlEscape(content);
        return "<html><body><p style='white-space: pre-wrap;'>" +
                content +
                "</p></body></html>";
    }
```

with:

```java
    @GetMapping(value = "/localFile", produces = MediaType.TEXT_HTML_VALUE)
    public String localFile(@RequestParam String filePath) throws IOException {
        String content = Files.readString(Paths.get(filePath));
        if (MarkdownPageRenderer.isMarkdownFile(filePath)) {
            return MarkdownPageRenderer.render(content);
        }
        content = HtmlUtils.htmlEscape(content);
        return "<html><body><p style='white-space: pre-wrap;'>" +
                content +
                "</p></body></html>";
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=IndexPageTest
```
Expected: PASS, all tests green (existing tests plus the two new ones).

- [ ] **Step 5: Run the full test suite and checkstyle**

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test checkstyle:check
```
Expected: all tests pass, checkstyle exits 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/breynisson/router/ui/IndexPage.java src/test/java/com/breynisson/router/ui/IndexPageTest.java
git commit -m "feat: render markdown files as HTML in /localFile"
```

- [ ] **Step 7: Manual verification**

Per this repo's testing conventions there's no browser-driven test for this endpoint, so confirm manually: build and run the app (working directory must be `digital-me-dev/`), then open `http://localhost:8080/localFile?filePath=<url-encoded path to a real .md file>` in a browser and confirm headings/lists/tables render formatted rather than as raw `#`/`-`/`|` characters, and that a `.txt` file still displays as plain escaped text as before.

---

## Documentation

- [ ] **Update `docs/architecture.md`**

Add a short note near the `/localFile` row of the REST API table (or just below the table) documenting the new behavior: `.md`/`.markdown` files are now rendered as formatted HTML via `MarkdownPageRenderer` (commonmark-java, GFM tables/strikethrough/task-lists, raw HTML in source is escaped); all other file types keep the existing escaped-plain-text behavior. Commit alongside or immediately after the code changes, per this repo's CLAUDE.md rule to update docs when committing a feature branch.

```bash
git add docs/architecture.md
git commit -m "docs: document markdown rendering for /localFile"
```
