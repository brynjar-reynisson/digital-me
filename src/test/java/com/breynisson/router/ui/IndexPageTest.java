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

class IndexPageTest {

    private TestDigitalMeStorage storage;
    private IndexPage indexPage;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        storage = new TestDigitalMeStorage();
        indexPage = new IndexPage(storage, null, text -> null, text -> null);
    }

    @Test
    void searchReturnsResultsFromStorage() {
        storage.addContent(request("http://example.com", "Example Page", "hello world content"));

        SearchResponse response = indexPage.search("hello");

        assertEquals(1, response.results().size());
        assertEquals("http://example.com", response.results().iterator().next().source());
    }

    @Test
    void searchReturnsEmptyWhenNoMatch() {
        SearchResponse response = indexPage.search("nonexistent");

        assertTrue(response.results().isEmpty());
    }

    @Test
    void addContentDelegatesToStorage() {
        AddContentResponse response = indexPage.addContent(request("http://example.com", "Example", "some content"));

        assertTrue(response.isSuccess());
        assertEquals(1, indexPage.search("some content").results().size());
    }

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

    private static AddContentRequest request(String source, String name, String content) {
        return AddContentRequests.of(source, name, content);
    }
}
