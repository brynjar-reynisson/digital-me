package com.breynisson.router.mcp;

/**
 * Configuration for the second-generation embedding model (bge-m3) that is dual-written and
 * searched alongside the legacy nomic-embed-text index during the gradual migration. Present only
 * when {@code embedding.v2.enabled=true}. The client is deliberately not an {@link EmbeddingClient}
 * bean, to keep injection of the legacy client unambiguous.
 */
public record V2Settings(EmbeddingClient client, String model, String documentPrefix,
                         String queryPrefix, float minScore) {}
