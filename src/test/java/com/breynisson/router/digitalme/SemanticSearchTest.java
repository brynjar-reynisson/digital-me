package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.PostgresTestSupport;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SemanticSearchTest {

    static String schema;

    @TempDir
    Path dataDir;

    @TempDir
    Path indexDir;

    @BeforeAll
    static void setUpDatabase() {
        schema = PostgresTestSupport.createIsolatedSchema("semanticsearch");
    }

    @AfterAll
    static void tearDownDatabase() {
        PostgresTestSupport.dropSchema(schema);
    }

    /**
     * MCP_EMBEDDING.EMBEDDING is declared extensions.VECTOR(768) NOT NULL (digital-me-db-1.sql) and
     * pgvector enforces that exact dimension on insert. The EmbeddingClient lambdas below return
     * short test vectors for readability, so each is zero-padded to 768 dims via {@link #v} before
     * EmbeddingIndex normalizes/stores it. Zero-padding both sides of a cosine comparison leaves the
     * dot product and magnitude (and so the cosine score) identical to the unpadded vectors, so this
     * doesn't change what any test asserts.
     */
    private static final int DIMENSIONS = 768;

    private static float[] v(float... values) {
        float[] padded = new float[DIMENSIONS];
        System.arraycopy(values, 0, padded, 0, values.length);
        return padded;
    }

    @BeforeEach
    void setUp() {
        LuceneIndex.setIndexPath(indexDir.toString());
        LuceneIndex.deleteIndex();
    }

    @AfterEach
    void tearDown() {
        LuceneIndex.deleteIndex();
    }

    private static void cleanup(String sourceUrl) {
        DatabaseAdapter.runSql("DELETE FROM SUMMARY_CACHE WHERE SOURCE_URL='" + sourceUrl + "'");
    }

    /**
     * MCP_EMBEDDING is a shared Postgres table backing real similarity search (EmbeddingIndex no
     * longer keeps a per-instance in-memory cache); the schema is created once per test class, so
     * without this, rows a test indexes via indexFile() would still be visible to every later
     * test's search() in this class, and score identically against the fixed test vectors above.
     */
    private static void cleanupEmbeddings(Path... files) {
        for (Path file : files) {
            DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING WHERE FILE_PATH='" + file.toAbsolutePath() + "'");
        }
    }

    private SemanticSearch semanticSearch(Function<String, String> summarizer, AtomicInteger callCount) {
        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        return new SemanticSearch(embeddingIndex, text -> {
            callCount.incrementAndGet();
            return summarizer.apply(text);
        }, dataDir.toString());
    }

    @Test
    void searchUsesMatchingChunkTextAsSnippetNotWholeFile() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("doc.txt");
        String body = "Filler sentence about nothing in particular. ".repeat(100)
                + "The matching answer about llamas is here. ".repeat(50);
        Files.writeString(file, "http://example.com/doc\n" + body);

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(
                text -> text.contains("llamas") ? v(1.0f, 0.0f) : v(0.0f, 1.0f),
                dataDir.toString());
        embeddingIndex.indexFile(file);

        SemanticSearch semanticSearch = new SemanticSearch(embeddingIndex, text -> null, dataDir.toString());
        List<SearchResult> results = semanticSearch.search("llamas");

        assertEquals(1, results.size());
        assertTrue(results.get(0).snippet().contains("llamas"),
                "Snippet should come from the matching chunk, not an arbitrary slice of the file: "
                        + results.get(0).snippet());

        cleanupEmbeddings(file);
    }

    @Test
    void searchPopulatesDisplayNameFromLuceneWhenAvailable() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("16-13-20-53-Project_Soulman.md.txt");
        Files.writeString(file, "http://example.com/soulman\nSoulman is a personal AI agent.");
        LuceneIndex.createOrUpdateIndex("Soulman is a personal AI agent.", "http://example.com/soulman", "Project Soulman.md");

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> v(1.0f), dataDir.toString());
        embeddingIndex.indexFile(file);

        SemanticSearch semanticSearch = new SemanticSearch(embeddingIndex, text -> null, dataDir.toString());
        List<SearchResult> results = semanticSearch.search("soulman");

        assertEquals(1, results.size());
        assertEquals("16-13-20-53-Project_Soulman.md.txt", results.get(0).name(),
                "name must stay the internal mcp-resources filename so the MCP fetch tool can still find it");
        assertEquals("Project Soulman.md", results.get(0).displayName());

        cleanupEmbeddings(file);
    }

    @Test
    void searchLeavesDisplayNameNullWhenNoLuceneEntryExists() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("claude-session.txt");
        Files.writeString(file, "claude://project/session-id\nUser: hello\nClaude: hi there");

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> v(1.0f), dataDir.toString());
        embeddingIndex.indexFile(file);

        SemanticSearch semanticSearch = new SemanticSearch(embeddingIndex, text -> null, dataDir.toString());
        List<SearchResult> results = semanticSearch.search("hello");

        assertEquals(1, results.size());
        assertNull(results.get(0).displayName());
        assertEquals("claude-session.txt", results.get(0).name());

        cleanupEmbeddings(file);
    }

    @Test
    void snippetStripsSourceUrlHeaderLine() {
        String raw = "http://example.com\nActual content here.";
        assertEquals("Actual content here.", SemanticSearch.snippet(raw));
    }

    @Test
    void chunkSnippetDoesNotStripFirstLine() {
        String chunkText = "First sentence of the chunk. Second sentence.";
        assertEquals(chunkText, SemanticSearch.chunkSnippet(chunkText));
    }

    @Test
    void firstCallForSourceInvokesSummarizerAndReturnsResult() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "a summary", calls);

        String result = semanticSearch.summarize("some text", "http://fresh-source.com");

        assertEquals("a summary", result);
        assertEquals(1, calls.get());
        cleanup("http://fresh-source.com");
    }

    @Test
    void secondCallForSameSourceReturnsCachedValueWithoutInvokingSummarizerAgain() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "a summary", calls);
        String source = "http://cached-source.com";

        semanticSearch.summarize("some text", source);
        String second = semanticSearch.summarize("different text", source);

        assertEquals("a summary", second);
        assertEquals(1, calls.get(), "summarizer should only be invoked once");
        cleanup(source);
    }

    @Test
    void nullResultIsNotCachedAndIsRetriedOnNextCall() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> null, calls);
        String source = "http://failing-source.com";

        String first = semanticSearch.summarize("some text", source);
        String second = semanticSearch.summarize("some text", source);

        assertNull(first);
        assertNull(second);
        assertEquals(2, calls.get(), "a failed call must be retried, not cached");
    }

    @Test
    void emptyResultIsNotCachedAndIsRetriedOnNextCall() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "", calls);
        String source = "http://empty-source.com";

        semanticSearch.summarize("some text", source);
        semanticSearch.summarize("some text", source);

        assertEquals(2, calls.get(), "an empty result must be retried, not cached");
    }

    @Test
    void nullSourceNeverTouchesTheCache() {
        AtomicInteger calls = new AtomicInteger();
        SemanticSearch semanticSearch = semanticSearch(text -> "a summary", calls);

        semanticSearch.summarize("some text", null);
        semanticSearch.summarize("some text", null);

        assertEquals(2, calls.get(), "without a source, every call must invoke the summarizer");
    }
}
