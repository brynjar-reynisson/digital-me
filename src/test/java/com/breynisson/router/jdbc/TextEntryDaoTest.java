package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.TextEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextEntryDaoTest {

    @TempDir
    static Path dbDir;

    @BeforeAll
    static void setUp() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("digital-me.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDown() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    @Test
    void insertFindAndDelete() {
        // given
        String name = "textEntryName";
        Instant instant = Instant.now();

        // when
        String uuid = TextEntryDao.insert(name, instant);
        TextEntry uuidEntry = TextEntryDao.findByUUID(uuid);

        // then
        assertNotNull(uuidEntry);
        assertEquals(instant, uuidEntry.instant);
        List<TextEntry> nameList = TextEntryDao.findByName(name);
        assertEquals(1, nameList.size());

        String key = "metadataKey";
        String value = "metadataValue";
        TextEntryMetadataDao.insert(uuid, key, value);
        String actualValue = TextEntryMetadataDao.get(uuid, key);
        assertEquals(value, actualValue);

        TextEntryDao.delete(uuid);
    }
}