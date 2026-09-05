package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.TextEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextEntryDaoTest {

    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("textentrydao");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
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

    @Test
    void findAllNameToTimeReturnsEveryEntryInOneCall() {
        // given
        Instant instantA = Instant.now().minusSeconds(60);
        Instant instantB = Instant.now();
        String uuidA = TextEntryDao.insert("nameA", instantA);
        String uuidB = TextEntryDao.insert("nameB", instantB);

        // when
        Map<String, Instant> nameToTime = TextEntryDao.findAllNameToTime();

        // then
        assertEquals(instantA, nameToTime.get("nameA"));
        assertEquals(instantB, nameToTime.get("nameB"));

        TextEntryDao.delete(uuidA);
        TextEntryDao.delete(uuidB);
    }
}