package com.breynisson.router;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.jdbc.AddContentQueueDao;
import com.breynisson.router.jdbc.model.AddContentQueueEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AddContentQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(AddContentQueueProcessor.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DigitalMeStorage storage;

    public AddContentQueueProcessor(DigitalMeStorage storage) {
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 2000)
    public void processPending() {
        for (AddContentQueueEntry entry : AddContentQueueDao.findAllOrderedByReceivedAt()) {
            processEntry(entry);
        }
    }

    private void processEntry(AddContentQueueEntry entry) {
        try {
            AddContentRequest request = MAPPER.readValue(entry.payload, AddContentRequest.class);
            storage.addContent(request);
            AddContentQueueDao.delete(entry.uuid);
        } catch (Exception e) {
            handleFailure(entry, e);
        }
    }

    private void handleFailure(AddContentQueueEntry entry, Exception e) {
        if (entry.attempts + 1 >= MAX_ATTEMPTS) {
            log.error("Dropping addContent queue entry {} after {} failed attempts", entry.uuid, MAX_ATTEMPTS, e);
            AddContentQueueDao.delete(entry.uuid);
        } else {
            log.warn("Failed to process addContent queue entry {} (attempt {})", entry.uuid, entry.attempts + 1, e);
            AddContentQueueDao.incrementAttempts(entry.uuid);
        }
    }
}
