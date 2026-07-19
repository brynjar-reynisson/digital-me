package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.AddContentQueueEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AddContentQueueDao {

    private static final String TABLE_NAME = "ADD_CONTENT_QUEUE";

    public static String insert(String payloadJson) {
        String uuid = UUID.randomUUID().toString();
        DatabaseAdapter.runPreparedStatement(
                "INSERT INTO " + TABLE_NAME + " (UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS) VALUES (?, ?, ?, 0)",
                uuid, payloadJson, DatabaseAdapter.instantToTime(Instant.now()));
        return uuid;
    }

    public static List<AddContentQueueEntry> findAllOrderedByReceivedAt() {
        return DatabaseAdapter.selectList(
                "SELECT UUID, PAYLOAD, RECEIVED_AT, ATTEMPTS FROM " + TABLE_NAME + " ORDER BY RECEIVED_AT ASC",
                new AddContentQueueEntry.ResultSetTransform());
    }

    public static void delete(String uuid) {
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE_NAME + " WHERE UUID=?", uuid);
    }

    public static void incrementAttempts(String uuid) {
        DatabaseAdapter.runPreparedStatement(
                "UPDATE " + TABLE_NAME + " SET ATTEMPTS = ATTEMPTS + 1 WHERE UUID=?", uuid);
    }
}
