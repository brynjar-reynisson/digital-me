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
        storage.addContent(request("http://unrelated-visir-seed.com", "Unrelated", "unrelated seed content"));

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

        cleanupDb("http://unrelated-visir-seed.com");
    }

    @Test
    void addContentExtractsVisirArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
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
        // Reproduces exactly what the server receives: content-script.js does
        // JSON.stringify(document.body.innerHTML), background.js wraps the whole
        // request in JSON.stringify(request) again, and Jackson decodes only the
        // outer envelope -- so getContent() is still JSON-string-escaped HTML,
        // not real HTML. ObjectMapper.writeValueAsString(html) reproduces that
        // single remaining layer of escaping exactly.
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.visir.is/g/456/team-wins-championship", "Team Wins", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("dramatic").results().size());
        assertTrue(storage.search("Unrelated").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://www.visir.is/g/456/team-wins-championship").isEmpty());

        cleanupDb("https://www.visir.is/g/456/team-wins-championship");
    }

    @Test
    void addContentExtractsDVArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <article class="node node--article">
                  <div class="node__content">
                    <div class="article-header">
                      <div class="field field--name-title">
                        <h1>Star Gets Called Out</h1>
                      </div>
                    </div>
                    <div class="article-body photoswipe-gallery">
                      <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                        <p>The player was criticized after photos surfaced from a party.</p>
                        <article class="media media--type-image media--view-mode-default">
                          <div class="field__label visually-hidden">Mynd</div>
                        </article>
                        <p>Fans reacted strongly to the news on social media.</p>
                      </div>
                    </div>
                    <div class="article-footer-region">
                      <h2>Fleiri fréttir</h2>
                      <div class="views-content">Unrelated Story About Something Else</div>
                    </div>
                  </div>
                </article>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.dv.is/433/2026/07/16/star-gets-called-out", "Star Gets Called Out", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("criticized").results().size());
        assertTrue(storage.search("Unrelated").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://www.dv.is/433/2026/07/16/star-gets-called-out").isEmpty());

        cleanupDb("https://www.dv.is/433/2026/07/16/star-gets-called-out");
    }

    @Test
    void addContentDiscardsDVFrontPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://www.dv.is");
        storage.addContent(request("http://unrelated-dv-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <div class="tarot-promo"><h4>Tarot Cards</h4></div>
                </div>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <p>Address 123<br>City</p>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.dv.is", "DV Front Page", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://www.dv.is").isEmpty());
        assertTrue(storage.search("Tarot").results().isEmpty());

        cleanupDb("http://unrelated-dv-seed.com");
    }

    @Test
    void addContentExtractsFotboltiArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="p-(--space-4)">
                  <h1 class="font-heading text-2xl font-bold uppercase leading-tight text-text-primary lg:text-3xl">Argentina Dominates After England Takes Lead</h1>
                  <div class="space-y-(--space-4)">
                    <div class="font-body text-base leading-8 text-text-primary">The manager faced heavy criticism after the team lost in the semi-final.</div>
                    <div class="lg:hidden"></div>
                    <a href="/news/other-article" class="flex overflow-hidden">
                      <h3 class="mt-(--space-1) text-base leading-normal">Related: Manager Defends His Decisions</h3>
                    </a>
                    <div class="article-html font-body text-base leading-8 text-text-primary">Statistics show a complete turnaround after the opening goal was scored.</div>
                  </div>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://fotbolti.net/news/16-07-2026/argentina-dominates", "Argentina Dominates", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("turnaround").results().size());
        assertTrue(storage.search("Defends").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://fotbolti.net/news/16-07-2026/argentina-dominates").isEmpty());

        cleanupDb("https://fotbolti.net/news/16-07-2026/argentina-dominates");
    }

    @Test
    void addContentDiscardsFotboltiFrontPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://fotbolti.net");
        storage.addContent(request("http://unrelated-fotbolti-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="space-y-(--space-2)">
                  <a href="/news/1"><span class="line-clamp-2">Argentina Dominates After England Takes Lead</span></a>
                  <a href="/news/2"><span class="line-clamp-2">Another Story Headline</span></a>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://fotbolti.net", "Fotbolti Front Page", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://fotbolti.net").isEmpty());
        assertTrue(storage.search("Dominates").results().isEmpty());

        cleanupDb("http://unrelated-fotbolti-seed.com");
    }

    private static AddContentRequest request(String source, String name, String content) {
        return AddContentRequests.of(source, name, content);
    }
}
