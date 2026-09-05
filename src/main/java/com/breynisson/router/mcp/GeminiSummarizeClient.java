package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls Google's free-tier Gemini {@code generateContent} endpoint to produce a short
 * summary. Returns {@code null} (and logs a warning) on any failure — missing API key,
 * non-200 response (including a 429 rate-limit), timeout, or network error — so callers
 * can fall back to another {@link SummarizeClient} without special-casing this one.
 */
public class GeminiSummarizeClient implements SummarizeClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiSummarizeClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public GeminiSummarizeClient(String baseUrl, String apiKey, String model, long timeoutSeconds, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    @Override
    public String summarize(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            String prompt = "Summarize the following in 2-3 sentences:\n\n" + text;
            String body = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String responseBody = response.body();
                String truncated = responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody;
                log.warn("Gemini generateContent returned HTTP {}: {}", response.statusCode(), truncated);
                return null;
            }
            return extractText(response.body());
        } catch (Exception e) {
            log.warn("Gemini summarize unavailable: {}",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1beta/models"))
                    .header("x-goog-api-key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (!textNode.isTextual()) {
            return null;
        }
        return textNode.asText().strip();
    }
}
