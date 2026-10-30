package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Background backfill that re-embeds the stored chunk text of legacy (nomic) rows with the
 * second-generation model, committing one file at a time so progress survives restarts.
 */
@Component
@ConditionalOnProperty(name = "embedding.v2.enabled", havingValue = "true")
public class EmbeddingMigrator {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMigrator.class);
    private static final int BATCH_SIZE = 25;
    private static final long BACKOFF_MILLIS = 30_000L;
    private static final long DEFAULT_POLL_MILLIS = 1000L;

    static final int PENALTY_THRESHOLD = 3;
    static final Duration PENALTY = Duration.ofMinutes(10);

    private final V2Settings v2;
    private final boolean skip;
    private final Clock clock;
    // Only touched by the single migrator thread (or a test thread)
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Instant> penalisedUntil = new HashMap<>();
    private final BooleanSupplier interactiveActive;
    private final long pollMillis;
    private boolean failing = false;

    @Autowired
    public EmbeddingMigrator(V2Settings v2, @Value("${digitalme.migrate-sqlite-path:}") String migrateSqlitePath,
                             EmbeddingIndex embeddingIndex,
                             @Value("${embedding.v2.migrator-idle-seconds:120}") int idleSeconds) {
        this(v2, !migrateSqlitePath.isBlank(), Clock.systemUTC(),
                idleSeconds <= 0 ? () -> false
                        : () -> embeddingIndex.searchedWithin(Duration.ofSeconds(idleSeconds)),
                DEFAULT_POLL_MILLIS);
    }

    public EmbeddingMigrator(V2Settings v2) {
        this(v2, Clock.systemUTC());
    }

    EmbeddingMigrator(V2Settings v2, Clock clock) {
        this(v2, false, clock, () -> false, DEFAULT_POLL_MILLIS);
    }

    EmbeddingMigrator(V2Settings v2, Clock clock, BooleanSupplier interactiveActive, long pollMillis) {
        this(v2, false, clock, interactiveActive, pollMillis);
    }

    private EmbeddingMigrator(V2Settings v2, boolean skip, Clock clock, BooleanSupplier interactiveActive,
                              long pollMillis) {
        this.v2 = v2;
        this.skip = skip;
        this.clock = clock;
        this.interactiveActive = interactiveActive;
        this.pollMillis = pollMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startOnReady() {
        if (skip) return;
        Thread t = new Thread(this::run, "embedding-migrator");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        while (true) {
            int committed = 0;
            try {
                committed = migrateBatch(BATCH_SIZE);
            } catch (Throwable e) {
                log.warn("Embedding migration batch failed", e);
            }
            if (committed == 0) {
                try {
                    Thread.sleep(BACKOFF_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Migrates up to {@code limit} files; returns how many were committed. */
    int migrateBatch(int limit) {
        Instant now = clock.instant();
        penalisedUntil.values().removeIf(until -> !until.isAfter(now));
        int committed = 0;
        int failed = 0;
        List<String> candidates = McpEmbeddingDao.findNextUnmigratedFilePaths(limit + penalisedUntil.size());
        int processed = 0;
        for (String filePath : candidates) {
            if (processed >= limit) break;
            if (penalisedUntil.containsKey(filePath)) continue;
            processed++;
            boolean ok = false;
            try {
                ok = migrateFile(filePath);
            } catch (Exception e) {
                if (failing) log.debug("Migration of {} failed", filePath, e);
                else log.warn("Migration of {} failed", filePath, e);
            }
            if (ok) {
                committed++;
                failures.remove(filePath);
            } else {
                failed++;
                int count = failures.merge(filePath, 1, Integer::sum);
                if (count >= PENALTY_THRESHOLD) {
                    penalisedUntil.put(filePath, now.plus(PENALTY));
                }
            }
        }
        if (failed > 0) {
            String msg = "Embedding migration: {} file(s) skipped this batch (embedding unavailable or file changed)";
            if (failing) log.debug(msg, failed);
            else log.warn(msg, failed);
            failing = true;
        } else if (failing && committed > 0) {
            log.info("Embedding migration recovered, {} file(s) committed", committed);
            failing = false;
        }
        return committed;
    }

    private boolean migrateFile(String filePath) {
        List<McpEmbeddingDao.ChunkToMigrate> chunks = McpEmbeddingDao.findUnmigratedChunks(filePath);
        if (chunks.isEmpty()) return false;
        String indexedAt = Instant.now().toString();
        List<McpEmbedding> rows = new ArrayList<>();
        for (McpEmbeddingDao.ChunkToMigrate chunk : chunks) {
            if (!yieldToInteractiveSearch()) return false;
            String text = v2.documentPrefix().isEmpty() ? chunk.chunkText() : v2.documentPrefix() + " " + chunk.chunkText();
            float[] embedding = v2.client().embed(text);
            if (embedding == null) return false;
            rows.add(new McpEmbedding(filePath, chunk.chunkIndex(), chunk.sourceUrl(), chunk.chunkText(),
                    EmbeddingIndex.normalize(embedding), v2.model(), indexedAt));
        }
        return McpEmbeddingDao.commitMigration(filePath, rows, indexedAt);
    }

    /** Blocks while an interactive search is active; returns false (interrupt flag restored) if interrupted. */
    private boolean yieldToInteractiveSearch() {
        if (!interactiveActive.getAsBoolean()) return true;
        log.info("Embedding migration pausing for interactive search");
        try {
            do {
                Thread.sleep(pollMillis);
            } while (interactiveActive.getAsBoolean());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        log.info("Embedding migration resuming after interactive search");
        return true;
    }
}
