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
            .sanitizeUrls(true)
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
