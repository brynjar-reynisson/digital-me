package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.TextEntry;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TextEntryDao {

    private static final String TABLE_NAME = "TEXT_ENTRY";

    public static String insert(String name, Instant instant) {
        String uuid = UUID.randomUUID().toString();
        if (instant == null) {
            instant = Instant.now();
        }
        String dateTimeStr = DatabaseAdapter.instantToTime(instant);
        DatabaseAdapter.runPreparedStatement("INSERT INTO " + TABLE_NAME + " (UUID, NAME, TIME) VALUES (?, ?, ?)", uuid, name, dateTimeStr);
        return uuid;
    }

    public static void update(TextEntry textEntry) {
        String dateTimeStr = DatabaseAdapter.instantToTime(textEntry.instant);
        DatabaseAdapter.runPreparedStatement("UPDATE " + TABLE_NAME + " SET NAME=?, TIME=? WHERE UUID=?", textEntry.name, dateTimeStr, textEntry.uuid);
    }

    public static TextEntry findByUUID(String uuid) {
        return DatabaseAdapter.selectOne("SELECT * FROM " + TABLE_NAME + " WHERE UUID=?", new TextEntry.ResultSetTransform(), uuid);
    }

    public static List<TextEntry> findByName(String name) {
        return DatabaseAdapter.selectList("SELECT * FROM " + TABLE_NAME + " WHERE NAME=?", new TextEntry.ResultSetTransform(), name);
    }

    public static Map<String, Instant> findAllNameToTime() {
        Map<String, Instant> nameToTime = new HashMap<>();
        for (TextEntry textEntry : DatabaseAdapter.selectList("SELECT * FROM " + TABLE_NAME, new TextEntry.ResultSetTransform())) {
            nameToTime.put(textEntry.name, textEntry.instant);
        }
        return nameToTime;
    }

    public static void insertOrUpdate(String source) {
        List<TextEntry> textEntries = findByName(source);
        if (!textEntries.isEmpty()) {
            TextEntry textEntry = textEntries.get(0);
            update(new TextEntry(textEntry.uuid, Instant.now(), textEntry.name));
        } else {
            insert(source, Instant.now());
        }
    }

    public static void delete(String uuid) {
        TextEntryMetadataDao.deleteByUUID(uuid);
        DatabaseAdapter.runPreparedStatement("DELETE FROM " + TABLE_NAME + " WHERE UUID=?", uuid);
    }
}
