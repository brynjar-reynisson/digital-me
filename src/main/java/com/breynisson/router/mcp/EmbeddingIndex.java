package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.model.McpEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores and queries dense vector embeddings for files in mcp-resources/.
 * Documents are split into chunks (see {@link Chunker}); each chunk gets its own row in the
 * MCP_EMBEDDING SQLite table. A unit-normalized vector cache is kept in memory for fast
 * dot-product scoring. Falls back gracefully when the EmbeddingClient (Ollama) is unavailable.
 */
@Component
public class EmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);
    private static final String DEFAULT_MODEL = "nomic-embed-text";

    private final EmbeddingClient embeddingClient;
    private final Path mcpResourcesDir;
    private final String model;
    private final String documentPrefix;
    private final String queryPrefix;
    private final float minScore;
    private final Map<CacheKey, CachedEmbedding> cache = new ConcurrentHashMap<>();

    @Autowired
    public EmbeddingIndex(
            EmbeddingClient embeddingClient,
            @Value("${data.dir:.}") String dataDir,
            @Value("${ollama.embedding.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${ollama.embedding.document-prefix:search_document:}") String documentPrefix,
            @Value("${ollama.embedding.query-prefix:search_query:}") String queryPrefix,
            @Value("${semantic-search.min-score:0.5}") float minScore) {
        this.embeddingClient = embeddingClient;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
        this.model = model;
        this.documentPrefix = documentPrefix;
        this.queryPrefix = queryPrefix;
        this.minScore = minScore;
    }

    /** Convenience constructor for tests: default model, no task prefixes, no score threshold. */
    public EmbeddingIndex(EmbeddingClient embeddingClient, String dataDir) {
        this(embeddingClient, dataDir, DEFAULT_MODEL, "", "", 0f);
    }

    private record CacheKey(String filePath, int chunkIndex) {}

    private record CachedEmbedding(String sourceUrl, String chunkText, float[] vector) {}

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
            Set<String> diskPaths = listFilePaths();
            reconcileStaleFiles(diskPaths);
            McpEmbeddingDao.deleteByModelNot(model);
            loadCacheFromDatabase();
            Set<String> indexed = McpEmbeddingDao.findAllFilePaths();
            for (String path : diskPaths) {
                if (!indexed.contains(path)) indexFile(Paths.get(path));
            }
        } catch (Exception e) {
            log.warn("Error during startup embedding indexing", e);
        }
    }

    private Set<String> listFilePaths() throws IOException {
        Set<String> paths = new HashSet<>();
        try (Stream<Path> walk = Files.walk(mcpResourcesDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> paths.add(f.toAbsolutePath().toString()));
        }
        return paths;
    }

    private void reconcileStaleFiles(Set<String> diskPaths) {
        for (String dbPath : McpEmbeddingDao.findAllFilePaths()) {
            if (!diskPaths.contains(dbPath)) {
                McpEmbeddingDao.deleteByFilePath(dbPath);
            }
        }
    }

    private void loadCacheFromDatabase() {
        cache.clear();
        for (McpEmbedding e : McpEmbeddingDao.findAll()) {
            cache.put(new CacheKey(e.filePath, e.chunkIndex),
                    new CachedEmbedding(e.sourceUrl, e.chunkText, fromBytes(e.embedding)));
        }
    }

    /** Generates and stores embeddings for the given file, one row per chunk. No-ops if Ollama is unavailable. */
    public void indexFile(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String sourceUrl = ResourceReceiver.firstLine(raw);
            int nl = raw.indexOf('\n');
            String body = nl >= 0 ? raw.substring(nl + 1) : raw;
            String filePath = file.toAbsolutePath().toString();
            String indexedAt = Instant.now().toString();

            List<McpEmbedding> rows = new ArrayList<>();
            List<float[]> vectors = new ArrayList<>();
            List<String> chunks = Chunker.chunk(body);
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String toEmbed = documentPrefix.isEmpty() ? chunkText : documentPrefix + " " + chunkText;
                float[] embedding = embeddingClient.embed(toEmbed);
                if (embedding == null) return; // Ollama unavailable — retry the whole file next pass
                float[] normalized = normalize(embedding);
                rows.add(new McpEmbedding(filePath, i, sourceUrl, chunkText, toBytes(normalized), model, indexedAt));
                vectors.add(normalized);
            }
            for (int i = 0; i < rows.size(); i++) {
                McpEmbedding row = rows.get(i);
                McpEmbeddingDao.upsert(row);
                cache.put(new CacheKey(row.filePath, row.chunkIndex),
                        new CachedEmbedding(row.sourceUrl, row.chunkText, vectors.get(i)));
            }
            log.debug("Indexed {} chunk(s) for {}", rows.size(), file.getFileName());
        } catch (Exception e) {
            log.warn("Error indexing embedding for {}", file, e);
        }
    }

    /**
     * Embeds the query and returns the top-K most similar files by cosine similarity,
     * deduplicated to each file's single best-scoring chunk.
     * Returns an empty list if Ollama is unavailable or no embeddings are stored.
     */
    public List<ScoredResult> findSimilar(String query, int topK) {
        String prefixedQuery = queryPrefix.isEmpty() ? query : queryPrefix + " " + query;
        float[] rawQueryEmbedding = embeddingClient.embed(prefixedQuery);
        if (rawQueryEmbedding == null) return List.of();
        float[] queryVector = normalize(rawQueryEmbedding);
        try {
            List<ScoredResult> scoredChunks = cache.entrySet().stream()
                    .map(e -> new ScoredResult(
                            e.getKey().filePath(),
                            e.getValue().sourceUrl(),
                            dot(queryVector, e.getValue().vector()),
                            e.getValue().chunkText()))
                    .filter(r -> r.score() >= minScore)
                    .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                    .toList();

            Map<String, ScoredResult> bestPerFile = new LinkedHashMap<>();
            for (ScoredResult r : scoredChunks) {
                bestPerFile.putIfAbsent(r.filePath(), r);
            }
            return bestPerFile.values().stream().limit(topK).toList();
        } catch (Exception e) {
            log.warn("Embedding search failed", e);
            return List.of();
        }
    }

    public record ScoredResult(String filePath, String sourceUrl, float score, String chunkText) {}

    private static float dot(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double sum = 0;
        for (int i = 0; i < len; i++) sum += a[i] * (double) b[i];
        return (float) sum;
    }

    private static float[] normalize(float[] v) {
        double magnitude = 0;
        for (float f : v) magnitude += f * (double) f;
        magnitude = Math.sqrt(magnitude);
        if (magnitude == 0) return v.clone();
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / magnitude);
        return out;
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
