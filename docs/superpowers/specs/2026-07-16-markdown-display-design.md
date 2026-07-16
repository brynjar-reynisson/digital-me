# Design: Markdown rendering for `/localFile`

## Problem

`GET /localFile?filePath=...` (`IndexPage.localFile()`) currently reads any local file, HTML-escapes it, and wraps it in `<p style='white-space: pre-wrap;'>...</p>`. For markdown files this shows the raw source — headings as `#` characters, tables as pipe-delimited text, etc. — instead of formatted output. This adds markdown-to-HTML rendering for `.md`/`.markdown` files while leaving every other file type's behavior unchanged.

## Design

### New dependencies (`pom.xml`)

`commonmark-java` (Apache 2.0, no transitive bloat):
- `org.commonmark:commonmark`
- `org.commonmark:commonmark-ext-gfm-tables`
- `org.commonmark:commonmark-ext-gfm-strikethrough`
- `org.commonmark:commonmark-ext-task-list-items`

### New class: `MarkdownPageRenderer` (`com.breynisson.router.ui`)

Static utility, same style as `LuceneIndex`:

- `isMarkdownFile(String filePath)` — `true` if the path, lower-cased, ends with `.md` or `.markdown`.
- `render(String markdownContent)` — builds a commonmark `Parser` with the three GFM extensions above, parses `markdownContent`, renders to an HTML fragment via `HtmlRenderer` built with `.escapeHtml(true)`. commonmark-java's `HtmlRenderer` defaults `escapeHtml` to `false` (raw HTML/script embedded in markdown source passes through unescaped, per the CommonMark spec's own raw-HTML-passthrough feature) — since today's endpoint always escapes everything, `.escapeHtml(true)` is set explicitly to keep that safe-by-default behavior for literal HTML tags typed into `.md` source, while standard markdown formatting (headings, bold, links, tables, etc.) renders normally. The fragment is then wrapped in a full, self-contained HTML page:
  - Inline `<style>` only — no external stylesheet or CDN, matching the rest of the app's self-contained-response style.
  - Centered content column (~800px max-width), system font stack, styled `code`/`pre` blocks, table borders/padding, blockquote left-border, comfortable line-height.
  - `@media (prefers-color-scheme: dark)` block for dark backgrounds/text — no toggle, just following the OS/browser preference.

### `IndexPage.localFile()` changes

```java
@GetMapping(value = "/localFile", produces = MediaType.TEXT_HTML_VALUE)
public String localFile(@RequestParam String filePath) throws IOException {
    String content = Files.readString(Paths.get(filePath));
    if (MarkdownPageRenderer.isMarkdownFile(filePath)) {
        return MarkdownPageRenderer.render(content);
    }
    content = HtmlUtils.htmlEscape(content);
    return "<html><body><p style='white-space: pre-wrap;'>" + content + "</p></body></html>";
}
```

Two changes to the existing method:
1. `produces = MediaType.TEXT_HTML_VALUE` is added. The mapping currently has no explicit `produces`, which leaves the response media type to Spring's content negotiation against the `StringHttpMessageConverter`'s default producible types (`text/plain`, `*/*`) — not guaranteed to resolve to `text/html`. Since correct rendering (for both the existing plain-text response and the new markdown response) depends on the browser receiving an actual `text/html` content type, this gap is closed while the method is already being touched.
2. The markdown branch is added ahead of the existing escape-and-wrap logic; non-markdown files fall through to that logic completely unchanged.

## Out of scope

- Obsidian-flavored syntax (`[[wikilinks]]`, `![[embeds]]`) — renders as literal plain text, not specially parsed.
- Resolving relative image paths (`![alt](photo.png)`) to a servable URL — the `<img>` tag is emitted as-is and will show broken in the browser unless the path is already an absolute `file://` or `http(s://)` URL. No new file-serving endpoint is added.
- Any change to how `.md` files are indexed, searched, or watched by `FileChangeWatcher` (which already recognizes `.txt`, `.md`, and `.pdf` today) — this is purely a display-time change to the `/localFile` endpoint.

## Testing

- `MarkdownPageRendererTest`:
  - `isMarkdownFile()`: `.md`, `.MD`, `.markdown` (and mixed case) return `true`; `.txt`, `.html`, no-extension, and a path that merely contains `md` mid-name return `false`.
  - `render()`: a fixture combining a heading, a list, a table, `~~strikethrough~~`, and a `- [ ]` task list item produces the corresponding HTML tags (`<h1>`/`<table>`/`<del>`/checkbox input, etc.) rather than literal markdown syntax.
  - `render()` on source containing a raw `<script>` tag confirms it comes out HTML-escaped in the output, not as an executable tag — locking in the explicit `.escapeHtml(true)` setting (commonmark-java's own default is unescaped raw-HTML passthrough).
- `IndexPageTest`: add a case with a `.md`-suffixed temp file confirming `localFile()` returns rendered HTML (e.g. asserts a `<h1>`-wrapped heading appears) rather than the raw `#`-prefixed escaped text; existing non-markdown behavior stays covered by not modifying its current assertions.
