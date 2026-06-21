package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Stores and queries dense vector embeddings for files in mcp-resources/.
 * Embeddings are persisted in the MCP_EMBEDDING SQLite table.
 * Falls back gracefully when the EmbeddingClient (Ollama) is unavailable.
 */
@Component
public class EmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);

    private final EmbeddingClient embeddingClient;
    private final Path mcpResourcesDir;

    public EmbeddingIndex(EmbeddingClient embeddingClient,
                          @Value("${data.dir:.}") String dataDir) {
        this.embeddingClient = embeddingClient;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
    }

    /** Indexes any mcp-resources files not yet in the embedding table. Runs async at startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void indexAllOnStartup() {
        Thread t = new Thread(this::indexAll, "embedding-indexer");
        t.setDaemon(true);
        t.start();
    }

    void indexAll() {
        try {
            if (!Files.isDirectory(mcpResourcesDir)) return;
            Set<String> indexed = McpEmbeddingDao.findAllFilePaths();
            try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    if (!indexed.contains(file.toAbsolutePath().toString())) indexFile(file);
                });
            }
        } catch (Exception e) {
            log.warn("Error during startup embedding indexing", e);
        }
    }

    // nomic-embed-text context limit tested at ~4 000 chars; use that as the safe cap
    private static final int MAX_EMBED_CHARS = 4_000;

    /** Generates and stores an embedding for the given file. No-ops if Ollama is unavailable. */
    public void indexFile(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String sourceUrl = ResourceReceiver.firstLine(raw);
            int nl = raw.indexOf('\n');
            String body = nl >= 0 ? raw.substring(nl + 1) : raw;
            String toEmbed = body.length() > MAX_EMBED_CHARS ? body.substring(0, MAX_EMBED_CHARS) : body;
            float[] embedding = embeddingClient.embed(toEmbed);
            if (embedding == null) return; // Ollama unavailable
            McpEmbeddingDao.upsert(new McpEmbedding(
                    file.toAbsolutePath().toString(), sourceUrl, toBytes(embedding), Instant.now().toString()));
            log.debug("Indexed embedding for {}", file.getFileName());
        } catch (Exception e) {
            log.warn("Error indexing embedding for {}", file, e);
        }
    }

    /**
     * Embeds the query and returns the top-K most similar files by cosine similarity.
     * Returns an empty list if Ollama is unavailable or no embeddings are stored.
     */
    public List<ScoredResult> findSimilar(String query, int topK) {
        float[] queryEmbedding = embeddingClient.embed(query);
        if (queryEmbedding == null) return List.of();
        try {
            return McpEmbeddingDao.findAll().stream()
                    .map(e -> new ScoredResult(e.filePath, e.sourceUrl, cosine(queryEmbedding, fromBytes(e.embedding))))
                    .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("Embedding search failed", e);
            return List.of();
        }
    }

    public record ScoredResult(String filePath, String sourceUrl, float score) {}

    private static float cosine(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0;
        double magA = 0;
        double magB = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            magA += a[i] * a[i];
            magB += b[i] * b[i];
        }
        double denominator = Math.sqrt(magA) * Math.sqrt(magB);
        return denominator == 0 ? 0f : (float) (dot / denominator);
    }

    private static byte[] toBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * Float.BYTES);
        for (float f : floats) buf.putFloat(f);
        return buf.array();
    }

    private static float[] fromBytes(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) floats[i] = buf.getFloat();
        return floats;
    }
}
