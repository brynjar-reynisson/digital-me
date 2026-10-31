package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls Ollama's local embedding endpoint to generate dense vectors.
 * Returns {@code null} (and logs a warning) if Ollama is not reachable.
 */
@Component
public class OllamaEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final String ollamaUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public OllamaEmbeddingClient(
            @Value("${ollama.url:http://localhost:11434}") String ollamaUrl,
            @Value("${ollama.embedding.model:nomic-embed-text}") String model,
            ObjectMapper objectMapper) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    // Hard cap after whitespace normalisation; leaves headroom for dense tokenisation
    private static final int MAX_CHARS = 3_000;

    /** Collapses whitespace runs and literal escape sequences left by the Chrome extension. */
    private static String normalise(String text) {
        // Replace literal \n \t \r escape sequences (2 chars each) with a space
        text = text.replace("\\n", " ").replace("\\t", " ").replace("\\r", " ");
        // Collapse any remaining whitespace runs to a single space
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * True if the text is empty once normalised, i.e. {@link #embed} would send Ollama an empty
     * prompt (for which it returns no embedding). Such chunks carry no content and are never embedded.
     */
    public static boolean isBlankAfterNormalisation(String text) {
        return normalise(text).isEmpty();
    }

    @Override
    public float[] embed(String text) {
        try {
            String normalised = normalise(text);
            String prompt = normalised.length() > MAX_CHARS ? normalised.substring(0, MAX_CHARS) : normalised;
            String body = objectMapper.writeValueAsString(
                    Map.of("model", model, "prompt", prompt, "options", Map.of("num_ctx", 2048)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Ollama returned HTTP {} (prompt chars={}): {}", response.statusCode(), prompt.length(), response.body());
                return null;
            }
            Map<?, ?> map = objectMapper.readValue(response.body(), Map.class);
            if (!(map.get("embedding") instanceof List<?> embedding) || embedding.isEmpty()) {
                log.warn("Ollama response missing 'embedding' field: {}", response.body());
                return null;
            }
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = ((Number) embedding.get(i)).floatValue();
            }
            return result;
        } catch (Exception e) {
            log.warn("Ollama embedding unavailable at {}: {}", ollamaUrl,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/"))
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
