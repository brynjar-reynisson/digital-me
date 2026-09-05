package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GeminiSummarizeClientTest {

    private static final String MODEL = "gemini-2.5-flash-lite";

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private GeminiSummarizeClient client(String apiKey) {
        return new GeminiSummarizeClient(baseUrl, apiKey, MODEL, 5, objectMapper);
    }

    private void respondWith(int status, Object body) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        server.createContext("/v1beta/models/" + MODEL + ":generateContent", exchange -> {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
    }

    @Test
    void summarizeParsesCandidateText() throws Exception {
        respondWith(200, Map.of("candidates", java.util.List.of(
                Map.of("content", Map.of("parts", java.util.List.of(Map.of("text", "A concise summary.")))))));

        assertEquals("A concise summary.", client("test-key").summarize("some long text"));
    }

    @Test
    void summarizeReturnsNullOnNon200() throws Exception {
        respondWith(429, Map.of("error", Map.of("message", "rate limited")));

        assertNull(client("test-key").summarize("some long text"));
    }

    @Test
    void summarizeReturnsNullWhenApiKeyBlank() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        server.createContext("/v1beta/models/" + MODEL + ":generateContent", exchange -> {
            called.set(true);
            exchange.sendResponseHeaders(200, -1);
        });

        assertNull(client("").summarize("some long text"));
        assertFalse(called.get(), "no HTTP call should be made when the API key is blank");
    }

    @Test
    void summarizeReturnsNullWhenCandidatesMissing() throws Exception {
        respondWith(200, Map.of("promptFeedback", Map.of("blockReason", "SAFETY")));

        assertNull(client("test-key").summarize("some long text"));
    }

    @Test
    void summarizeReturnsNullWhenServerUnreachable() {
        server.stop(0);

        assertNull(client("test-key").summarize("some long text"));
    }

    @Test
    void isAvailableTrueOn200FromModelsEndpoint() throws Exception {
        server.createContext("/v1beta/models", exchange -> exchange.sendResponseHeaders(200, -1));

        assertTrue(client("test-key").isAvailable());
    }

    @Test
    void isAvailableFalseWhenApiKeyBlank() {
        assertFalse(client("").isAvailable());
    }

    @Test
    void summarizeSendsApiKeyOnlyInHeaderNeverInUrl() throws Exception {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        AtomicReference<String> capturedHeader = new AtomicReference<>();
        server.createContext("/v1beta/models/" + MODEL + ":generateContent", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            capturedHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of("candidates", java.util.List.of(
                    Map.of("content", Map.of("parts", java.util.List.of(Map.of("text", "ok")))))));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        client("test-key").summarize("some long text");

        assertNull(capturedQuery.get(), "API key must never appear in the query string");
        assertEquals("test-key", capturedHeader.get());
    }
}
