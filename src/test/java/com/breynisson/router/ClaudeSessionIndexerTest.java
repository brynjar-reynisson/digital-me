package com.breynisson.router;

import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.SummaryCacheDao;
import com.breynisson.router.mcp.EmbeddingIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionIndexerTest {

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
    void buildFileNameUsesDayHourMinuteSecondPrefixLikeOtherMcpResources() {
        LocalDateTime sessionStart = LocalDateTime.of(2026, 7, 16, 16, 15, 13);

        String fileName = ClaudeSessionIndexer.buildFileName("digital-me", sessionStart);

        assertEquals("16-16-15-13-claudecode-digital-me.txt", fileName);
    }

    @Test
    void deleteOldResourceFilesRemovesCachedSummaryForThatSource() {
        EmbeddingIndex embeddingIndex = new EmbeddingIndex(text -> null, dataDir.toString());
        ClaudeSessionIndexer indexer = new ClaudeSessionIndexer(embeddingIndex, dataDir.toString());
        String sourceUrl = "claude://some-project/some-session";
        SummaryCacheDao.upsert(sourceUrl, "cached summary");

        indexer.deleteOldResourceFiles(sourceUrl);

        assertNull(SummaryCacheDao.find(sourceUrl));
    }
}
