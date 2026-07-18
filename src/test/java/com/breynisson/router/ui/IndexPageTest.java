package com.breynisson.router.ui;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.digitalme.AddContentResponse;
import com.breynisson.router.digitalme.SearchResponse;
import com.breynisson.router.digitalme.TestDigitalMeStorage;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IndexPageTest {

    private TestDigitalMeStorage storage;
    private IndexPage indexPage;

    @TempDir
    Path tempDir;

    @TempDir
    static Path dbDir;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    private static byte[] embeddingBytes() {
        ByteBuffer buf = ByteBuffer.allocate(Float.BYTES);
        buf.putFloat(1.0f);
        return buf.array();
    }

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
        TextEntryDao.insert(mdFile.toString(), null);

        String html = indexPage.localFile(mdFile.toString());

        assertTrue(html.contains("<h1>Hello World</h1>"));
    }

    @Test
    void localFileEscapesPlainTextFile() throws IOException {
        Path txtFile = tempDir.resolve("notes.txt");
        Files.writeString(txtFile, "# Not a heading");
        TextEntryDao.insert(txtFile.toString(), null);

        String html = indexPage.localFile(txtFile.toString());

        assertTrue(html.contains("# Not a heading"));
        assertFalse(html.contains("<h1>"));
    }

    @Test
    void localFileRejectsPathNotInIndex() throws IOException {
        Path txtFile = tempDir.resolve("unindexed.txt");
        Files.writeString(txtFile, "secret content");

        assertThrows(ResponseStatusException.class, () -> indexPage.localFile(txtFile.toString()));
    }

    @Test
    void localFileRejectsUncPaths() {
        assertThrows(ResponseStatusException.class,
                () -> indexPage.localFile("\\\\attacker.example.com\\share\\file.txt"));
    }

    @Test
    void localFileResolvesNonPathSourceUrlViaEmbeddingIndex() throws IOException {
        String sourceUrl = "claude://some-project/some-uuid";
        Path resourceFile = tempDir.resolve("16-10-00-00-claudecode-some-project.txt");
        Files.writeString(resourceFile, sourceUrl + "\nHello from claude session");
        McpEmbeddingDao.upsert(new McpEmbedding(resourceFile.toAbsolutePath().toString(), 0, sourceUrl,
                "Hello from claude session", embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        String html = indexPage.localFile(sourceUrl);

        assertTrue(html.contains("Hello from claude session"));
    }

    private static AddContentRequest request(String source, String name, String content) {
        return AddContentRequests.of(source, name, content);
    }
}
