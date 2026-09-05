package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Calls Ollama's local generate endpoint to produce a short summary.
 * Returns {@code null} (and logs a warning) if Ollama is not reachable.
 */
public class OllamaSummarizeClient implements SummarizeClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaSummarizeClient.class);

    private final String ollamaUrl;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public OllamaSummarizeClient(
            String ollamaUrl,
            String model,
            ObjectMapper objectMapper) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public String summarize(String text) {
        try {
            String prompt = "Summarize the following in 2-3 sentences:\n\n" + text;
            String body = objectMapper.writeValueAsString(
                    Map.of("model", model, "prompt", prompt, "stream", Boolean.FALSE));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Ollama generate returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            Map<?, ?> map = objectMapper.readValue(response.body(), Map.class);
            Object resp = map.get("response");
            return resp instanceof String s ? s.strip() : null;
        } catch (Exception e) {
            log.warn("Ollama summarize unavailable at {}: {}", ollamaUrl,
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
