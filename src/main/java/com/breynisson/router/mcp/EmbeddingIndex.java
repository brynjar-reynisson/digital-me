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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Epoch millis of the last interactive search; 0 = never. */
    private volatile long lastSearchMillis;


    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);
    private static final String DEFAULT_MODEL = "nomic-embed-text";
    private static final int RRF_K = 60;

    private final EmbeddingClient embeddingClient;
    private final Path mcpResourcesDir;
    private final String model;
    private final String documentPrefix;
    private final String queryPrefix;
    private final float minScore;
    private final boolean migrating;
    private final V2Settings v2;

    @Autowired
    public EmbeddingIndex(
            EmbeddingClient embeddingClient,
            @Value("${data.dir:.}") String dataDir,
            @Value("${ollama.embedding.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${ollama.embedding.document-prefix:search_document:}") String documentPrefix,
            @Value("${ollama.embedding.query-prefix:search_query:}") String queryPrefix,
            @Value("${semantic-search.min-score:0.5}") float minScore,
            @Value("${digitalme.migrate-sqlite-path:}") String migrateSqlitePath,
            Optional<V2Settings> v2) {
        this.v2 = v2.orElse(null);
        this.embeddingClient = embeddingClient;
        this.mcpResourcesDir = Paths.get(dataDir, ResourceReceiver.MCP_RESOURCES_DIR);
        this.model = model;
        this.documentPrefix = documentPrefix;
        this.queryPrefix = queryPrefix;
        this.minScore = minScore;
        this.migrating = !migrateSqlitePath.isBlank();
    }

    /** Constructor without a second-generation model. */
    public EmbeddingIndex(
            EmbeddingClient embeddingClient, String dataDir, String model, String documentPrefix,
            String queryPrefix, float minScore, String migrateSqlitePath) {
        this(embeddingClient, dataDir, model, documentPrefix, queryPrefix, minScore, migrateSqlitePath,
                Optional.empty());
    }

    /** Convenience constructor for tests: default model, no task prefixes, no score threshold. */
    public EmbeddingIndex(EmbeddingClient embeddingClient, String dataDir) {
        this(embeddingClient, dataDir, DEFAULT_MODEL, "", "", 0f, "");
    }

    /** Convenience constructor for tests with a second-generation model configured. */
    public EmbeddingIndex(EmbeddingClient embeddingClient, String dataDir, V2Settings v2) {
        this(embeddingClient, dataDir, DEFAULT_MODEL, "", "", 0f, "", Optional.ofNullable(v2));
    }

    /** Migration progress; present only when the second-generation model is configured. */
    public Optional<McpEmbeddingDao.MigrationStatus> migrationStatus() {
        return v2 == null ? Optional.empty() : Optional.of(McpEmbeddingDao.migrationStatus());
    }

    /**
     * Indexes any mcp-resources files not yet in the embedding table. Runs async at startup.
     * Skipped during the one-time SQLite-to-Postgres migration run: this would otherwise
     * re-embed the entire mcp-resources tree via Ollama concurrently with the migrator's own
     * bulk copy of already-computed vectors into the same MCP_EMBEDDING table.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void indexAllOnStartup() {
        if (migrating) return;
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
            List<com.breynisson.router.jdbc.model.McpEmbedding> v2Rows = v2 == null ? null : new ArrayList<>();
            List<String> chunks = Chunker.chunk(body);
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String toEmbed = documentPrefix.isEmpty() ? chunkText : documentPrefix + " " + chunkText;
                float[] embedding = embeddingClient.embed(toEmbed);
                if (embedding == null) return; // Ollama unavailable — retry the whole file next pass
                float[] normalized = normalize(embedding);
                rows.add(new com.breynisson.router.jdbc.model.McpEmbedding(
                        filePath, i, sourceUrl, chunkText, normalized, model, indexedAt));
                if (v2Rows != null) {
                    String v2Text = v2.documentPrefix().isEmpty() ? chunkText : v2.documentPrefix() + " " + chunkText;
                    float[] v2Embedding = v2.client().embed(v2Text);
                    if (v2Embedding == null) {
                        v2Rows = null; // v2 unavailable — write legacy rows unmarked; background migration catches up
                    } else {
                        v2Rows.add(new com.breynisson.router.jdbc.model.McpEmbedding(
                                filePath, i, sourceUrl, chunkText, normalize(v2Embedding), v2.model(), indexedAt));
                    }
                }
            }
            if (v2Rows != null) {
                McpEmbeddingDao.upsertDual(rows, v2Rows, indexedAt);
            } else {
                for (com.breynisson.router.jdbc.model.McpEmbedding row : rows) {
                    McpEmbeddingDao.upsert(row);
                }
            }
            log.debug("Indexed {} chunk(s) for {}", rows.size(), file.getFileName());
        } catch (Exception e) {
            log.warn("Error indexing embedding for {}", file, e);
        }
    }

    /** True if an interactive semantic search started less than {@code window} ago (false if none ever). */
    public boolean searchedWithin(java.time.Duration window) {
        long last = lastSearchMillis;
        return last != 0 && System.currentTimeMillis() - last < window.toMillis();
    }

    /**
     * Embeds the query and returns the top-K most similar files by cosine similarity,
     * deduplicated to each source URL's single best-scoring chunk. Returns an empty list if
     * Ollama is unavailable or no embeddings are stored.
     */
    public List<ScoredResult> findSimilar(String query, int topK) {
        lastSearchMillis = System.currentTimeMillis();
        String prefixedQuery = queryPrefix.isEmpty() ? query : queryPrefix + " " + query;
        float[] rawQueryEmbedding = embeddingClient.embed(prefixedQuery);
        float[] queryVector = rawQueryEmbedding == null ? null : normalize(rawQueryEmbedding);

        float[] v2QueryVector = null;
        if (v2 != null) {
            String v2Query = v2.queryPrefix().isEmpty() ? query : v2.queryPrefix() + " " + query;
            float[] raw = v2.client().embed(v2Query);
            if (raw != null) v2QueryVector = normalize(raw);
        }

        List<McpEmbeddingDao.ScoredMatch> oldSide = List.of();
        List<McpEmbeddingDao.ScoredMatch> v2Side = List.of();
        try {
            if (queryVector != null) {
                // Hide migrated rows only when the v2 side can actually serve them
                oldSide = McpEmbeddingDao.findSimilar(McpEmbeddingDao.TABLE, queryVector, minScore, topK,
                        v2 != null && v2QueryVector != null);
            }
        } catch (Exception e) {
            log.warn("Embedding search failed", e);
        }
        try {
            if (v2QueryVector != null) {
                v2Side = McpEmbeddingDao.findSimilar(McpEmbeddingDao.TABLE_V2, v2QueryVector, v2.minScore(), topK, false);
            }
        } catch (Exception e) {
            log.warn("Embedding v2 search failed", e);
        }
        if (v2 == null) {
            return oldSide.stream().map(EmbeddingIndex::toResult).toList();
        }
        return fuse(oldSide, v2Side, topK);
    }

    /** Reciprocal rank fusion of the two sides on sourceUrl; each result keeps its best-ranked entry's own score. */
    private static List<ScoredResult> fuse(
            List<McpEmbeddingDao.ScoredMatch> a, List<McpEmbeddingDao.ScoredMatch> b, int topK) {
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, McpEmbeddingDao.ScoredMatch> best = new LinkedHashMap<>();
        Map<String, Integer> bestRank = new LinkedHashMap<>();
        for (List<McpEmbeddingDao.ScoredMatch> side : List.of(a, b)) {
            for (int i = 0; i < side.size(); i++) {
                McpEmbeddingDao.ScoredMatch m = side.get(i);
                int rank = i + 1;
                fused.merge(m.sourceUrl(), 1.0 / (RRF_K + rank), Double::sum);
                Integer prev = bestRank.get(m.sourceUrl());
                if (prev == null || rank < prev) {
                    bestRank.put(m.sourceUrl(), rank);
                    best.put(m.sourceUrl(), m);
                }
            }
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> toResult(best.get(e.getKey())))
                .toList();
    }

    private static ScoredResult toResult(McpEmbeddingDao.ScoredMatch m) {
        return new ScoredResult(m.filePath(), m.sourceUrl(), m.score(), m.chunkText());
    }

    public record ScoredResult(String filePath, String sourceUrl, float score, String chunkText) {}

    static float[] normalize(float[] v) {
        double magnitude = 0;
        for (float f : v) magnitude += f * (double) f;
        magnitude = Math.sqrt(magnitude);
        if (magnitude == 0) return v.clone();
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / magnitude);
        return out;
    }
}
