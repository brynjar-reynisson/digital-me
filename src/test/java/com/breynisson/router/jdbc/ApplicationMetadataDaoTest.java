package com.breynisson.router.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationMetadataDaoTest {

    static String schema;

    @BeforeAll
    static void setUp() {
        schema = PostgresTestSupport.createIsolatedSchema("applicationmetadatadao");
    }

    @AfterAll
    static void tearDown() {
        PostgresTestSupport.dropSchema(schema);
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