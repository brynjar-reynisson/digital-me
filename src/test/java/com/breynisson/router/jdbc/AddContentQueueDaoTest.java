package com.breynisson.router.jdbc;

import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddContentQueueDaoTest {

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

    @Test
    void insertThenFindRoundTripsPayload() {
        String uuid = AddContentQueueDao.insert("{\"source\":\"http://example.com\"}");

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();

        AddContentQueueEntry found = entries.stream().filter(e -> e.uuid.equals(uuid)).findFirst().orElseThrow();
        assertEquals("{\"source\":\"http://example.com\"}", found.payload);
        assertEquals(0, found.attempts);
        assertNotNull(found.receivedAt);

        AddContentQueueDao.delete(uuid);
    }

    @Test
    void findAllOrderedByReceivedAtReturnsEntriesInNonDecreasingOrder() {
        String uuid1 = AddContentQueueDao.insert("{\"source\":\"http://a.com\"}");
        String uuid2 = AddContentQueueDao.insert("{\"source\":\"http://b.com\"}");
        String uuid3 = AddContentQueueDao.insert("{\"source\":\"http://c.com\"}");

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();

        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).receivedAt.compareTo(entries.get(i).receivedAt) <= 0,
                    "entries must be non-decreasing by receivedAt");
        }

        AddContentQueueDao.delete(uuid1);
        AddContentQueueDao.delete(uuid2);
        AddContentQueueDao.delete(uuid3);
    }

    @Test
    void deleteRemovesOnlyTargetedEntry() {
        String uuid1 = AddContentQueueDao.insert("{\"source\":\"http://keep.com\"}");
        String uuid2 = AddContentQueueDao.insert("{\"source\":\"http://remove.com\"}");

        AddContentQueueDao.delete(uuid2);

        List<String> remainingUuids = AddContentQueueDao.findAllOrderedByReceivedAt().stream()
                .map(e -> e.uuid).toList();
        assertTrue(remainingUuids.contains(uuid1));
        assertFalse(remainingUuids.contains(uuid2));

        AddContentQueueDao.delete(uuid1);
    }

    @Test
    void incrementAttemptsIncrementsOnlyTargetedEntry() {
        String uuid1 = AddContentQueueDao.insert("{\"source\":\"http://one.com\"}");
        String uuid2 = AddContentQueueDao.insert("{\"source\":\"http://two.com\"}");

        AddContentQueueDao.incrementAttempts(uuid1);
        AddContentQueueDao.incrementAttempts(uuid1);

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();
        AddContentQueueEntry entry1 = entries.stream().filter(e -> e.uuid.equals(uuid1)).findFirst().orElseThrow();
        AddContentQueueEntry entry2 = entries.stream().filter(e -> e.uuid.equals(uuid2)).findFirst().orElseThrow();
        assertEquals(2, entry1.attempts);
        assertEquals(0, entry2.attempts);

        AddContentQueueDao.delete(uuid1);
        AddContentQueueDao.delete(uuid2);
    }
}
