package com.breynisson.router.mcp;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResourceReceiverTest {

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

    private static byte[] embeddingBytes() {
        ByteBuffer buf = ByteBuffer.allocate(Float.BYTES);
        buf.putFloat(1.0f);
        return buf.array();
    }

    @Test
    void addContentWritesSourceLineThenContent() throws IOException {
        ResourceReceiver receiver = new ResourceReceiver(dataDir.toString());
        AddContentRequest req = AddContentRequests.of("http://example.com", "Example", "hello world");

        Path written = receiver.addContent(req);

        String content = Files.readString(written);
        assertEquals("http://example.com", ResourceReceiver.firstLine(content));
        assertTrue(content.endsWith("hello world"));
    }

    @Test
    void deleteExistingForRemovesPriorFileAndEmbeddingForSameSource() throws IOException {
        ResourceReceiver receiver = new ResourceReceiver(dataDir.toString());
        String sourceUrl = "http://same-source.com";

        Path staleFile = receiver.addContent(AddContentRequests.of(sourceUrl, "Old", "old content"));
        McpEmbeddingDao.upsert(new McpEmbedding(staleFile.toAbsolutePath().toString(), 0, sourceUrl,
                "old content", embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        SummaryCacheDao.upsert(sourceUrl, "cached summary");

        receiver.deleteExistingFor(sourceUrl);

        assertFalse(Files.exists(staleFile), "Stale resource file should be deleted");
        assertTrue(McpEmbeddingDao.findFilePathsBySourceUrl(sourceUrl).isEmpty(),
                "Stale embedding rows should be deleted");
        assertNull(SummaryCacheDao.find(sourceUrl), "Cached summary should be deleted");
    }

    @Test
    void deleteExistingForLeavesOtherSourcesUntouched() throws IOException {
        ResourceReceiver receiver = new ResourceReceiver(dataDir.toString());

        Path keepFile = receiver.addContent(AddContentRequests.of("http://keep.com", "Keep", "keep content"));
        McpEmbeddingDao.upsert(new McpEmbedding(keepFile.toAbsolutePath().toString(), 0, "http://keep.com",
                "keep content", embeddingBytes(), "nomic-embed-text", "2026-01-01T00:00:00Z"));
        SummaryCacheDao.upsert("http://keep.com", "keep summary");

        receiver.deleteExistingFor("http://different-source.com");

        assertTrue(Files.exists(keepFile));
        assertFalse(McpEmbeddingDao.findFilePathsBySourceUrl("http://keep.com").isEmpty());
        assertEquals("keep summary", SummaryCacheDao.find("http://keep.com"));
    }

    @Test
    void deleteExistingForIsNoOpWhenNothingIndexedForSource() {
        ResourceReceiver receiver = new ResourceReceiver(dataDir.toString());

        assertDoesNotThrow(() -> receiver.deleteExistingFor("http://never-indexed.com"));
    }
}
