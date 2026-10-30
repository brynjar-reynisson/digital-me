package com.breynisson.router.ui;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.digitalme.AddContentResponse;
import com.breynisson.router.digitalme.SearchResponse;
import com.breynisson.router.digitalme.TestDigitalMeStorage;
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.PostgresTestSupport;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import com.breynisson.router.jdbc.model.McpEmbedding;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.V2Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IndexPageTest {

    private TestDigitalMeStorage storage;
    private IndexPage indexPage;

    @TempDir
    Path tempDir;

    static String schema;

    @BeforeAll
    static void setUpDatabase() {
        schema = PostgresTestSupport.createIsolatedSchema("indexpage");
    }

    @AfterAll
    static void tearDownDatabase() {
        PostgresTestSupport.dropSchema(schema);
    }

    /**
     * MCP_EMBEDDING.EMBEDDING is declared extensions.VECTOR(768) NOT NULL (digital-me-db-1.sql) and
     * pgvector enforces that exact dimension on insert. The test vector below is zero-padded to 768
     * dims so inserts succeed; zero-padding doesn't change what any test in this file asserts, since
     * these embeddings are never used in a similarity comparison here.
     */
    private static final int DIMENSIONS = 768;

    private static float[] embeddingBytes() {
        float[] padded = new float[DIMENSIONS];
        padded[0] = 1.0f;
        return padded;
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
    void healthIndexOmitsMigrationWhenV2Disabled() {
        Map<String, Object> health = indexPage.indexHealth();

        assertEquals(Set.of("indexedFiles", "totalChunks", "totalFilesOnDisk", "coveragePercent"),
                health.keySet());
    }

    @Test
    void healthIndexReportsMigrationProgressWhenV2Enabled() {
        clearEmbeddingTables();
        IndexPage page = indexPageWithV2();
        String migratedA = tempDir.resolve("migrated-a.txt").toAbsolutePath().toString();
        String migratedB = tempDir.resolve("migrated-b.txt").toAbsolutePath().toString();
        String unmigratedA = tempDir.resolve("unmigrated-a.txt").toAbsolutePath().toString();
        String unmigratedB = tempDir.resolve("unmigrated-b.txt").toAbsolutePath().toString();
        try {
            McpEmbeddingDao.upsert(legacyRow(migratedA, "source-migrated-a"));
            McpEmbeddingDao.upsert(legacyRow(migratedB, "source-migrated-b"));
            McpEmbeddingDao.upsert(McpEmbeddingDao.TABLE_V2, v2Row(migratedA, "source-migrated-a"));
            McpEmbeddingDao.upsert(McpEmbeddingDao.TABLE_V2, v2Row(migratedB, "source-migrated-b"));
            DatabaseAdapter.runSql("UPDATE MCP_EMBEDDING SET MIGRATED_AT = '2026-01-02T00:00:00Z' "
                    + "WHERE SOURCE_URL IN ('source-migrated-a', 'source-migrated-b')");
            McpEmbeddingDao.upsert(legacyRow(unmigratedA, "source-unmigrated-a"));
            McpEmbeddingDao.upsert(legacyRow(unmigratedB, "source-unmigrated-b"));

            @SuppressWarnings("unchecked")
            Map<String, Object> migration = (Map<String, Object>) page.indexHealth().get("migration");

            assertEquals(2, migration.get("migratedFiles"));
            assertEquals(2, migration.get("remainingFiles"));
            assertEquals(2, migration.get("migratedChunks"));
            assertEquals(2, migration.get("remainingChunks"));
            assertEquals(50.0, migration.get("percentMigrated"));
        } finally {
            clearEmbeddingTables();
        }
    }

    @Test
    void percentMigratedIs100WhenNoChunks() {
        clearEmbeddingTables();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> migration = (Map<String, Object>) indexPageWithV2().indexHealth().get("migration");

            assertEquals(0, migration.get("migratedChunks"));
            assertEquals(0, migration.get("remainingChunks"));
            assertEquals(100.0, migration.get("percentMigrated"));
        } finally {
            clearEmbeddingTables();
        }
    }

    private IndexPage indexPageWithV2() {
        V2Settings v2 = new V2Settings(text -> null, "bge-m3", "", "", 0f);
        return new IndexPage(storage, null, text -> null, text -> null,
                new EmbeddingIndex(text -> null, tempDir.toString(), v2));
    }

    private static void clearEmbeddingTables() {
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING");
        DatabaseAdapter.runSql("DELETE FROM MCP_EMBEDDING_V2");
    }

    private static McpEmbedding legacyRow(String filePath, String sourceUrl) {
        return new McpEmbedding(filePath, 0, sourceUrl, "text for " + sourceUrl,
                embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z");
    }

    /** MCP_EMBEDDING_V2 is declared vector(1024) for bge-m3 (digital-me-db-3.sql). */
    private static McpEmbedding v2Row(String filePath, String sourceUrl) {
        return new McpEmbedding(filePath, 0, sourceUrl, "text for " + sourceUrl,
                new float[1024], "bge-m3", "2026-01-01T00:00:00Z");
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
