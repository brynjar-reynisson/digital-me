package com.breynisson.router.digitalme;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticSearchTest {

    @TempDir
    static Path dbDir;

    @TempDir
    Path dataDir;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    @Test
    void searchUsesMatchingChunkTextAsSnippetNotWholeFile() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("mcp-resources").resolve("2026-03"));
        Path file = dir.resolve("doc.txt");
        String body = "Filler sentence about nothing in particular. ".repeat(100)
                + "The matching answer about llamas is here. ".repeat(50);
        Files.writeString(file, "http://example.com/doc\n" + body);

        EmbeddingIndex embeddingIndex = new EmbeddingIndex(
                text -> text.contains("llamas") ? new float[]{1.0f, 0.0f} : new float[]{0.0f, 1.0f},
                dataDir.toString());
        embeddingIndex.indexFile(file);

        SemanticSearch semanticSearch = new SemanticSearch(embeddingIndex, text -> null, dataDir.toString());
        List<SearchResult> results = semanticSearch.search("llamas");

        assertEquals(1, results.size());
        assertTrue(results.get(0).snippet().contains("llamas"),
                "Snippet should come from the matching chunk, not an arbitrary slice of the file: "
                        + results.get(0).snippet());
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
}
