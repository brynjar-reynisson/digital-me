package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDigitalMeStorageTest {

    @TempDir
    static Path dbDir;

    @TempDir
    static Path dataDir;

    @TempDir
    Path indexDir;

    private DefaultDigitalMeStorage storage;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    @BeforeEach
    void setUp() {
        LuceneIndex.setIndexPath(indexDir.toString());
        LuceneIndex.deleteIndex();
        storage = new DefaultDigitalMeStorage(dataDir.toString(), new EmbeddingIndex(text -> null, dataDir.toString()));
    }

    @AfterEach
    void tearDown() {
        LuceneIndex.deleteIndex();
    }

    private void cleanupDb(String source) {
        TextEntryDao.findByName(source).forEach(e -> TextEntryDao.delete(e.uuid));
    }

    @Test
    void addContentIndexesPlainTextAndPersists() {
        AddContentRequest req = request("/local/file.txt", "file.txt", "searchable text content");

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        SearchResponse results = storage.search("searchable");
        assertEquals(1, results.results().size());
        assertEquals("/local/file.txt", results.results().iterator().next().source());
        assertFalse(TextEntryDao.findByName("/local/file.txt").isEmpty());

        cleanupDb("/local/file.txt");
    }

    @Test
    void addContentStripsHtmlForHttpSources() {
        String html = "<html><body><p>hello world</p></body></html>";
        AddContentRequest req = request("http://example.com", "Example", html);

        storage.addContent(req);

        SearchResponse results = storage.search("hello");
        assertEquals(1, results.results().size());
        // Verify plain-text was indexed, not raw HTML tags
        SearchResponse noHtmlTag = storage.search("body");
        assertTrue(noHtmlTag.results().isEmpty());

        cleanupDb("http://example.com");
    }

    @Test
    void addContentUpdatesExistingEntry() {
        AddContentRequest req = request("http://example.com", "Page", "first version");
        storage.addContent(req);
        String uuidAfterFirst = TextEntryDao.findByName("http://example.com").get(0).uuid;

        req.setContent("second version");
        storage.addContent(req);

        var entries = TextEntryDao.findByName("http://example.com");
        assertEquals(1, entries.size());
        assertEquals(uuidAfterFirst, entries.get(0).uuid);
        assertEquals(1, storage.search("second").results().size());

        cleanupDb("http://example.com");
    }

    @Test
    void addContentReturnsFailureOnError() {
        AddContentRequest req = new AddContentRequest();
        req.setSource(null);
        req.setName(null);
        req.setContent(null);

        AddContentResponse response = storage.addContent(req);

        assertFalse(response.isSuccess());
        assertNotNull(response.getErrorMessage());
    }

    @Test
    void searchReturnsResultsInRelevanceOrder() {
        storage.addContent(request("http://a.com", "A", "shoes"));
        storage.addContent(request("http://b.com", "B", "golden shoes"));
        storage.addContent(request("http://c.com", "C", "golden golden shoes"));

        SearchResponse response = storage.search("golden shoes");

        assertEquals(3, response.results().size());
        // Most relevant (both terms, higher frequency) should come first
        assertEquals("http://c.com", response.results().iterator().next().source());

        cleanupDb("http://a.com");
        cleanupDb("http://b.com");
        cleanupDb("http://c.com");
    }

    @Test
    void addContentDiscardsCoveredScreenshotUrl() {
        cleanupDb("https://www.facebook.com/somepost");
        storage.addContent(request("http://unrelated.com", "Unrelated", "unrelated seed content"));

        AddContentRequest req = request("https://www.facebook.com/somepost", "Facebook Post", "some facebook post text");
        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(storage.search("facebook").results().isEmpty());
        assertTrue(TextEntryDao.findByName("https://www.facebook.com/somepost").isEmpty());

        cleanupDb("http://unrelated.com");
    }

    @Test
    void addContentStoresUncoveredLinkedinSubpage() {
        AddContentRequest req = request("https://www.linkedin.com/pulse/some-article", "Some Article", "article body text");

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("article").results().size());
        assertFalse(TextEntryDao.findByName("https://www.linkedin.com/pulse/some-article").isEmpty());

        cleanupDb("https://www.linkedin.com/pulse/some-article");
    }

    private static AddContentRequest request(String source, String name, String content) {
        return AddContentRequests.of(source, name, content);
    }
}
