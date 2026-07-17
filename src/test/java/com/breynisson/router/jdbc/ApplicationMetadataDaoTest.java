package com.breynisson.router.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationMetadataDaoTest {

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
    void insertGetAndDelete() {
        // given
        String testKey = "test-key";
        String testValue = "testValue";
        ApplicationMetadataDao.deleteValue(testKey); // ensure clean state

        // when
        ApplicationMetadataDao.insert(testKey, testValue);

        // then
        String actualValue = ApplicationMetadataDao.getValue(testKey);
        assertEquals(testValue, actualValue);
        ApplicationMetadataDao.deleteValue(testKey);
    }

}