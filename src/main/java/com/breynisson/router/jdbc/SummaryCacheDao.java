package com.breynisson.router.jdbc;

import java.time.Instant;

public class SummaryCacheDao {

    private static final String TABLE = "SUMMARY_CACHE";

    public static String find(String sourceUrl) {
        return DatabaseAdapter.selectOne(
                "SELECT SUMMARY FROM " + TABLE + " WHERE SOURCE_URL = ?",
                DatabaseAdapter.RESULT_SET_STRING_TRANSFORM, sourceUrl);
    }

    public static void upsert(String sourceUrl, String summary) {
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + TABLE + " (SOURCE_URL, SUMMARY, CREATED_AT) VALUES (?, ?, ?) "
              + "ON CONFLICT (SOURCE_URL) DO UPDATE SET SUMMARY = EXCLUDED.SUMMARY, CREATED_AT = EXCLUDED.CREATED_AT",
                sourceUrl, summary, DatabaseAdapter.instantToTime(Instant.now()));
    }

    public static void deleteBySourceUrl(String sourceUrl) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE + " WHERE SOURCE_URL = ?", sourceUrl);
    }
}
