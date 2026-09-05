package com.breynisson.router.mcp;

import com.breynisson.router.jdbc.McpEmbeddingDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Indexes and queries dense vector embeddings for files in mcp-resources/.
 * Documents are split into chunks (see {@link Chunker}); each chunk gets its own row in the
 * MCP_EMBEDDING Postgres table (pgvector column, HNSW-indexed). Falls back gracefully when
 * the EmbeddingClient (Ollama) is unavailable.
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
            Set<String> indexed = McpEmbeddingDao.findAllFilePaths();
            for (String path : diskPaths) {
                if (!indexed.contains(path)) indexFile(Paths.get(path));
            }
        } catch (Exception e) {
            log.warn("Error during startup embedding indexing", e);
        }
    }

    /** Counts files currently on disk under mcp-resources/. Returns 0 if the directory doesn't exist. */
    public int countFilesOnDisk() {
        try {
            if (!Files.isDirectory(mcpResourcesDir)) return 0;
            return listFilePaths().size();
        } catch (IOException e) {
            log.warn("Could not count files in {}", mcpResourcesDir, e);
            return 0;
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

    /** Generates and stores embeddings for the given file, one row per chunk. No-ops if Ollama is unavailable. */
    public void indexFile(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String sourceUrl = ResourceReceiver.firstLine(raw);
            int nl = raw.indexOf('\n');
            String body = nl >= 0 ? raw.substring(nl + 1) : raw;
            String filePath = file.toAbsolutePath().toString();
            String indexedAt = Instant.now().toString();

            List<com.breynisson.router.jdbc.model.McpEmbedding> rows = new ArrayList<>();
            List<String> chunks = Chunker.chunk(body);
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String toEmbed = documentPrefix.isEmpty() ? chunkText : documentPrefix + " " + chunkText;
                float[] embedding = embeddingClient.embed(toEmbed);
                if (embedding == null) return; // Ollama unavailable — retry the whole file next pass
                float[] normalized = normalize(embedding);
                rows.add(new com.breynisson.router.jdbc.model.McpEmbedding(
                        filePath, i, sourceUrl, chunkText, normalized, model, indexedAt));
            }
            for (com.breynisson.router.jdbc.model.McpEmbedding row : rows) {
                McpEmbeddingDao.upsert(row);
            }
            log.debug("Indexed {} chunk(s) for {}", rows.size(), file.getFileName());
        } catch (Exception e) {
            log.warn("Error indexing embedding for {}", file, e);
        }
    }

    /**
     * Embeds the query and returns the top-K most similar files by cosine similarity,
     * deduplicated to each source URL's single best-scoring chunk. Returns an empty list if
     * Ollama is unavailable or no embeddings are stored.
     */
    public List<ScoredResult> findSimilar(String query, int topK) {
        String prefixedQuery = queryPrefix.isEmpty() ? query : queryPrefix + " " + query;
        float[] rawQueryEmbedding = embeddingClient.embed(prefixedQuery);
        if (rawQueryEmbedding == null) return List.of();
        float[] queryVector = normalize(rawQueryEmbedding);
        try {
            return McpEmbeddingDao.findSimilar(queryVector, minScore, topK).stream()
                    .map(m -> new ScoredResult(m.filePath(), m.sourceUrl(), m.score(), m.chunkText()))
                    .toList();
        } catch (Exception e) {
            log.warn("Embedding search failed", e);
            return List.of();
        }
    }

    public record ScoredResult(String filePath, String sourceUrl, float score, String chunkText) {}

    private static float[] normalize(float[] v) {
        double magnitude = 0;
        for (float f : v) magnitude += f * (double) f;
        magnitude = Math.sqrt(magnitude);
        if (magnitude == 0) return v.clone();
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / magnitude);
        return out;
    }
}
