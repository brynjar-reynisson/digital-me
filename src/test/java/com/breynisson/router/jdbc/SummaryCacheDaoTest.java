package com.breynisson.router.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SummaryCacheDaoTest {

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

    private static void cleanup(String sourceUrl) {
        DatabaseAdapter.runSql("DELETE FROM SUMMARY_CACHE WHERE SOURCE_URL='" + sourceUrl + "'");
    }

    @Test
    void findReturnsNullForUnknownSource() {
        assertNull(SummaryCacheDao.find("http://never-cached.com"));
    }

    @Test
    void upsertThenFindRoundTripsTheSummary() {
        String source = "http://round-trip.com";
        SummaryCacheDao.upsert(source, "a summary");

        assertEquals("a summary", SummaryCacheDao.find(source));
        cleanup(source);
    }

    @Test
    void upsertTwiceForSameSourceReplacesRatherThanDuplicates() {
        String source = "http://replace-me.com";
        SummaryCacheDao.upsert(source, "old summary");
        SummaryCacheDao.upsert(source, "new summary");

        assertEquals("new summary", SummaryCacheDao.find(source));
        cleanup(source);
    }

    @Test
    void deleteBySourceUrlRemovesTheEntry() {
        String source = "http://delete-me.com";
        SummaryCacheDao.upsert(source, "a summary");

        SummaryCacheDao.deleteBySourceUrl(source);

        assertNull(SummaryCacheDao.find(source));
    }

    @Test
    void deleteBySourceUrlLeavesOtherEntriesUntouched() {
        String keep = "http://keep-cached.com";
        String drop = "http://drop-cached.com";
        SummaryCacheDao.upsert(keep, "keep summary");
        SummaryCacheDao.upsert(drop, "drop summary");

        SummaryCacheDao.deleteBySourceUrl(drop);

        assertEquals("keep summary", SummaryCacheDao.find(keep));
        assertNull(SummaryCacheDao.find(drop));
        cleanup(keep);
    }
}
