package com.breynisson.router;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentRequests;
import com.breynisson.router.digitalme.DefaultDigitalMeStorage;
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddContentQueueProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path dbDir;

    @TempDir
    static Path dataDir;

    @TempDir
    Path indexDir;

    private AddContentQueueProcessor processor;
    private DefaultDigitalMeStorage storage;

    @BeforeAll
    static void setUpDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(dbDir.resolve("test.db").toString());
        DatabaseAdapter.init();
    }

    @AfterAll
    static void tearDownDatabase() {
        DatabaseAdapter.setDefaultDatabasePath(null);
    }

    @BeforeEach
    void setUp() {
        LuceneIndex.setIndexPath(indexDir.toString());
        LuceneIndex.deleteIndex();
        storage = new DefaultDigitalMeStorage(dataDir.toString(), new EmbeddingIndex(text -> null, dataDir.toString()));
        processor = new AddContentQueueProcessor(storage);
    }

    @AfterEach
    void tearDown() {
        LuceneIndex.deleteIndex();
    }

    private void cleanupDb(String source) {
        TextEntryDao.findByName(source).forEach(e -> TextEntryDao.delete(e.uuid));
    }

    private static String payloadFor(AddContentRequest request) throws Exception {
        return MAPPER.writeValueAsString(request);
    }

    @Test
    void processPendingIndexesQueuedEntryAndRemovesIt() throws Exception {
        AddContentRequest request = AddContentRequests.of("http://queued.com", "Queued Page", "queued searchable content");
        String uuid = AddContentQueueDao.insert(payloadFor(request));

        processor.processPending();

        assertEquals(1, storage.search("queued").results().size());
        assertTrue(AddContentQueueDao.findAllOrderedByReceivedAt().stream().noneMatch(e -> e.uuid.equals(uuid)));

        cleanupDb("http://queued.com");
    }

    @Test
    void processPendingSurvivesAcrossSimulatedRestart() throws Exception {
        // A row inserted but never processed (as if the JVM died before the first scheduled
        // tick) must still be there and get picked up by a fresh processPending() call.
        AddContentRequest request = AddContentRequests.of("http://survives-restart.com", "Page", "content surviving a crash");
        String uuid = AddContentQueueDao.insert(payloadFor(request));

        AddContentQueueProcessor freshProcessor = new AddContentQueueProcessor(storage);
        freshProcessor.processPending();

        assertEquals(1, storage.search("surviving").results().size());
        assertTrue(AddContentQueueDao.findAllOrderedByReceivedAt().stream().noneMatch(e -> e.uuid.equals(uuid)));

        cleanupDb("http://survives-restart.com");
    }

    @Test
    void poisonPillPayloadIsDroppedAfterMaxAttempts() {
        String uuid = AddContentQueueDao.insert("not valid json");

        for (int i = 0; i < 5; i++) {
            processor.processPending();
        }

        assertTrue(AddContentQueueDao.findAllOrderedByReceivedAt().stream().noneMatch(e -> e.uuid.equals(uuid)),
                "entry should be dropped after MAX_ATTEMPTS failed attempts");
    }

    @Test
    void poisonPillPayloadIncrementsAttemptsBeforeMaxAttemptsReached() {
        String uuid = AddContentQueueDao.insert("not valid json");

        processor.processPending();

        List<AddContentQueueEntry> entries = AddContentQueueDao.findAllOrderedByReceivedAt();
        AddContentQueueEntry entry = entries.stream().filter(e -> e.uuid.equals(uuid)).findFirst().orElseThrow();
        assertEquals(1, entry.attempts);

        AddContentQueueDao.delete(uuid);
    }
}
