package com.breynisson.router.ui;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.digitalme.AddContentResponse;
import com.breynisson.router.digitalme.SearchResponse;
import com.breynisson.router.digitalme.TestDigitalMeStorage;
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import com.breynisson.router.jdbc.model.McpEmbedding;
import com.breynisson.router.mcp.EmbeddingIndex;
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
import java.util.List;
import java.util.Map;

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
        indexPage = new IndexPage(storage, null, text -> null, text -> null, new EmbeddingIndex(text -> null, tempDir.toString()));
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
    void indexHealthReportsCoverageMetrics() throws IOException {
        Path mcpResourcesDir = tempDir.resolve("mcp-resources");
        Files.createDirectories(mcpResourcesDir);
        Path indexedFile = mcpResourcesDir.resolve("indexed.txt");
        Files.writeString(indexedFile, "source-indexed\ncontent");
        Files.writeString(mcpResourcesDir.resolve("unindexed.txt"), "source-unindexed\ncontent");
        McpEmbeddingDao.upsert(new McpEmbedding(indexedFile.toAbsolutePath().toString(), 0, "source-indexed",
                "content", embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));

        try {
            Map<String, Object> health = indexPage.indexHealth();

            assertEquals(1, health.get("indexedFiles"));
            assertEquals(1, health.get("totalChunks"));
            assertEquals(2, health.get("totalFilesOnDisk"));
            assertEquals(50.0, health.get("coveragePercent"));
        } finally {
            McpEmbeddingDao.deleteByFilePath(indexedFile.toAbsolutePath().toString());
        }
    }

    @Test
    void indexHealthReturns100PercentWhenNoFilesOnDisk() {
        IndexPage page = new IndexPage(storage, null, text -> null, text -> null,
                new EmbeddingIndex(text -> null, tempDir.resolve("empty").toString()));

        Map<String, Object> health = page.indexHealth();

        assertEquals(0, health.get("totalFilesOnDisk"));
        assertEquals(100.0, health.get("coveragePercent"));
    }

    @Test
    void addContentQueuesRequestInsteadOfProcessingSynchronously() throws Exception {
        AddContentResponse response = indexPage.addContent(request("http://example.com", "Example", "some content"));

        assertTrue(response.isSuccess());
        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();
        AddContentQueueEntry entry = entries.stream()
                .filter(e -> e.payload.contains("http://example.com"))
                .findFirst().orElseThrow();
        assertTrue(entry.payload.contains("some content"));
        // Not processed synchronously: TestDigitalMeStorage never saw this request, so it's not searchable yet.
        assertTrue(indexPage.search("some content").results().isEmpty());

        AddContentQueueDao.delete(entry.uuid);
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
