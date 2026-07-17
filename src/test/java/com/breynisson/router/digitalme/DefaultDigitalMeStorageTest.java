package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

    private List<Path> findMcpResourceFilesFor(String sourceUrl) throws IOException {
        Path mcpResourcesDir = dataDir.resolve("mcp-resources");
        if (!Files.isDirectory(mcpResourcesDir)) return List.of();
        try (var walk = Files.walk(mcpResourcesDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> {
                        try {
                            String content = Files.readString(f);
                            return sourceUrl.equals(ResourceReceiver.firstLine(content));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
    }

    private static byte[] fakeEmbeddingBytes() {
        ByteBuffer buf = ByteBuffer.allocate(Float.BYTES);
        buf.putFloat(1.0f);
        return buf.array();
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
    void addContentReplacesPriorResourceFileOnResubmission() throws IOException {
        String source = "http://resubmitted.com";
        cleanupDb(source);

        storage.addContent(request(source, "Page", "first version content"));
        List<Path> filesAfterFirst = findMcpResourceFilesFor(source);
        assertEquals(1, filesAfterFirst.size());
        Path staleFile = filesAfterFirst.get(0);
        // Simulate the async embedding pipeline having already indexed the first file
        // (avoids depending on CompletableFuture.runAsync timing in the test).
        McpEmbeddingDao.upsert(new McpEmbedding(
                staleFile.toAbsolutePath().toString(), 0, source, "first version content",
                fakeEmbeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        assertEquals(Set.of(staleFile.toAbsolutePath().toString()),
                McpEmbeddingDao.findFilePathsBySourceUrl(source),
                "sanity check: embedding row should be visible before resubmission");

        storage.addContent(request(source, "Page", "second version content"));

        assertTrue(McpEmbeddingDao.findFilePathsBySourceUrl(source).isEmpty(),
                "Prior embedding rows should be deleted on resubmission");
        List<Path> filesAfterSecond = findMcpResourceFilesFor(source);
        assertEquals(1, filesAfterSecond.size(),
                "Resubmitting the same source should replace the prior resource file, not accumulate a second one");
        // ResourceReceiver names files with second-granularity timestamps, so a same-second
        // resubmission can legitimately reuse the prior filename after it's deleted -- assert on
        // content, not path identity, to prove the old version was actually replaced.
        String survivingContent = Files.readString(filesAfterSecond.get(0));
        assertTrue(survivingContent.contains("second version content"));
        assertFalse(survivingContent.contains("first version content"));
        assertEquals(1, storage.search("second").results().size());
        assertTrue(storage.search("first").results().isEmpty());

        cleanupDb(source);
    }

    @Test
    void addContentDiscardsSelfReferentialLocalFileUrl() {
        String selfReferentialUrl = "https://digitalme.breynisson.org/localFile?filePath=C%3A%2FUsers%2FLenovo%2Fnote.md";
        cleanupDb(selfReferentialUrl);
        storage.addContent(request("http://unrelated.com", "Unrelated", "unrelated seed content"));

        AddContentRequest req = request(selfReferentialUrl, "note.md", "rendered page text content");
        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(storage.search("rendered page text").results().isEmpty());
        assertTrue(TextEntryDao.findByName(selfReferentialUrl).isEmpty());

        cleanupDb("http://unrelated.com");
    }

    @Test
    void addContentDiscardsSelfReferentialLocalFileUrlEvenWithoutHttpScheme() {
        // Real-world data showed a Chrome-extension capture with a malformed source URL missing
        // its scheme prefix (e.g. "digitalme.breynisson.org/localFile?..." instead of "https://...").
        // A check nested only inside the startsWith("http") branch would miss this entirely.
        String schemeLessSelfReferentialUrl = "digitalme.breynisson.org/localFile?filePath=C%3A%2FUsers%2FLenovo%2Fnote.md";
        cleanupDb(schemeLessSelfReferentialUrl);
        storage.addContent(request("http://unrelated2.com", "Unrelated", "unrelated seed content"));

        AddContentRequest req = request(schemeLessSelfReferentialUrl, "note.md", "rendered page text content");
        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(storage.search("rendered page text").results().isEmpty());
        assertTrue(TextEntryDao.findByName(schemeLessSelfReferentialUrl).isEmpty());

        cleanupDb("http://unrelated2.com");
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
        assertEquals(1, storage.search("criticism").results().size());
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

    @Test
    void addContentFallsBackAndReportsOnceWhenVisirLayoutChanges() throws java.io.IOException {
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
        assertTrue(reportContent.contains("https://www.visir.is/g/111/team-wins-championship"));
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

    @Test
    void addContentExtractsCNNArticleBodyFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline__text" id="maincontent">Meteorite Sheds Light on Ancient Water</h1>
                <div class="article__content" itemprop="articleBody">
                  <p data-component-name="paragraph">A meteorite that crashed through the roof of a home could shed light on ancient water in the solar system.</p>
                  <div data-component-name="related-content" class="related-content">
                    <p class="related-content__headline">Related article: Scientists found something else in an asteroid</p>
                  </div>
                  <p data-component-name="paragraph">Only one fragment was recovered from the meteorite.</p>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://edition.cnn.com/2026/07/15/science/meteorite-ancient-water", "Meteorite", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("fragment").results().size());
        assertEquals(1, storage.search("roof").results().size());
        assertTrue(storage.search("Scientists found something else").results().isEmpty());
        assertFalse(TextEntryDao.findByName("https://edition.cnn.com/2026/07/15/science/meteorite-ancient-water").isEmpty());

        cleanupDb("https://edition.cnn.com/2026/07/15/science/meteorite-ancient-water");
    }

    @Test
    void addContentDiscardsCNNFrontPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://edition.cnn.com");
        storage.addContent(request("http://unrelated-cnn-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <div class="zone">
                  <a href="/2026/07/15/science/some-article"><span class="headline">Meteorite Sheds Light on Ancient Water</span></a>
                </div>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://edition.cnn.com", "CNN Front Page", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://edition.cnn.com").isEmpty());
        assertTrue(storage.search("Meteorite Sheds Light").results().isEmpty());

        cleanupDb("http://unrelated-cnn-seed.com");
    }

    @Test
    void addContentExtractsCNNLiveBlogFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline_live-story__text" id="maincontent">Iran War Live Updates</h1>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Iran signals openness to diplomacy</h2>
                  <p data-component-name="paragraph">Officials say Iran remains open to diplomatic talks despite recent tensions.</p>
                </article>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Trump responds to latest developments</h2>
                  <p data-component-name="paragraph">The president addressed reporters about the ongoing situation in the region.</p>
                </article>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump", "Iran War Live Updates", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("diplomatic").results().size());
        assertEquals(1, storage.search("reporters").results().size());
        assertFalse(TextEntryDao.findByName("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump").isEmpty());

        cleanupDb("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump");
    }

    @Test
    void addContentExtractsRedditPostAndCommentsFromRealExtensionPayloadShape() throws com.fasterxml.jackson.core.JsonProcessingException {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <h1 slot="title">Should I confront my roommate about this?</h1>
                <shreddit-post-text-body slot="text-body" view-context="CommentsPage">
                  <div property="schema:articleBody">
                    <p>My roommate has been leaving dishes in the sink for two weeks straight and I am at my wit's end.</p>
                  </div>
                </shreddit-post-text-body>
                <shreddit-comment thingid="t1_abc123" depth="0">
                  <div slot="comment">Just talk to them directly, passive aggressive notes never work.</div>
                </shreddit-comment>
                <shreddit-comment thingid="t1_def456" depth="1">
                  <div slot="comment">Agreed, communication is key in any living situation.</div>
                </shreddit-comment>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.reddit.com/r/relationships/comments/1abcxyz/should_i_confront_my_roommate_about_this/", "Should I confront my roommate", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("dishes").results().size());
        assertEquals(1, storage.search("passive").results().size());
        assertEquals(1, storage.search("communication").results().size());
        assertFalse(TextEntryDao.findByName("https://www.reddit.com/r/relationships/comments/1abcxyz/should_i_confront_my_roommate_about_this/").isEmpty());

        cleanupDb("https://www.reddit.com/r/relationships/comments/1abcxyz/should_i_confront_my_roommate_about_this/");
    }

    @Test
    void addContentDiscardsRedditFeedPage() throws com.fasterxml.jackson.core.JsonProcessingException {
        cleanupDb("https://www.reddit.com/r/relationships/");
        storage.addContent(request("http://unrelated-reddit-seed.com", "Unrelated", "unrelated seed content"));

        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a post about relocating to a new city for work.</div>
                </shreddit-post>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a different post about a weekend trip.</div>
                </shreddit-post>
                </body>
                </html>
                """;
        String extensionShapedPayload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(html);
        AddContentRequest req = request("https://www.reddit.com/r/relationships/", "Relationships Subreddit", extensionShapedPayload);

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(TextEntryDao.findByName("https://www.reddit.com/r/relationships/").isEmpty());
        assertTrue(storage.search("relocating").results().isEmpty());

        cleanupDb("http://unrelated-reddit-seed.com");
    }

    private static AddContentRequest request(String source, String name, String content) {
        return AddContentRequests.of(source, name, content);
    }
}
