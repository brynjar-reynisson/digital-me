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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    private final V2Settings v2;
    private final boolean skip;

    @Autowired
    public EmbeddingMigrator(V2Settings v2, @Value("${digitalme.migrate-sqlite-path:}") String migrateSqlitePath) {
        this.v2 = v2;
        this.skip = !migrateSqlitePath.isBlank();
    }

    public EmbeddingMigrator(V2Settings v2) {
        this(v2, "");
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
        int committed = 0;
        int failed = 0;
        for (String filePath : McpEmbeddingDao.findNextUnmigratedFilePaths(limit)) {
            try {
                if (migrateFile(filePath)) {
                    committed++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.debug("Migration of {} failed", filePath, e);
            }
        }
        if (failed > 0) {
            log.warn("Embedding migration: {} file(s) skipped this batch (embedding unavailable or file changed)", failed);
        }
        return committed;
    }

    private boolean migrateFile(String filePath) {
        List<McpEmbeddingDao.ChunkToMigrate> chunks = McpEmbeddingDao.findUnmigratedChunks(filePath);
        if (chunks.isEmpty()) return false;
        String indexedAt = Instant.now().toString();
        List<McpEmbedding> rows = new ArrayList<>();
        for (McpEmbeddingDao.ChunkToMigrate chunk : chunks) {
            String text = v2.documentPrefix().isEmpty() ? chunk.chunkText() : v2.documentPrefix() + " " + chunk.chunkText();
            float[] embedding = v2.client().embed(text);
            if (embedding == null) return false;
            rows.add(new McpEmbedding(filePath, chunk.chunkIndex(), chunk.sourceUrl(), chunk.chunkText(),
                    EmbeddingIndex.normalize(embedding), v2.model(), indexedAt));
        }
        return McpEmbeddingDao.commitMigration(filePath, rows, indexedAt);
    }
}
