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
