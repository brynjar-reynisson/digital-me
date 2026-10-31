package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingClientTest {

    private HttpServer server;
    private OllamaEmbeddingClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int port = server.getAddress().getPort();
        client = new OllamaEmbeddingClient("http://localhost:" + port, "nomic-embed-text", objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respondWith(int status, Object body) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        server.createContext("/api/embeddings", exchange -> {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
    }

    @Test
    void embedReturnsParsedFloatArray() throws Exception {
        respondWith(200, Map.of("embedding", List.of(0.1, 0.2, 0.3)));

        float[] result = client.embed("hello world");

        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 0.001f);
        assertEquals(0.2f, result[1], 0.001f);
        assertEquals(0.3f, result[2], 0.001f);
    }

    @Test
    void embedReturnsNullOnHttp500() throws Exception {
        respondWith(500, Map.of("error", "the input length exceeds the context length"));

        assertNull(client.embed("text"));
    }

    @Test
    void embedReturnsNullWhenServerUnreachable() {
        server.stop(0);
        assertNull(client.embed("text"));
    }

    @Test
    void embedReturnsNullWhenEmbeddingFieldMissing() throws Exception {
        respondWith(200, Map.of("model", "nomic-embed-text")); // no "embedding" key

        assertNull(client.embed("text"));
    }

    @Test
    void embedNormalisesLiteralEscapeSequences() throws Exception {
        String[] capturedPrompt = {null};
        server.createContext("/api/embeddings", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<?, ?> req = objectMapper.readValue(body, Map.class);
                capturedPrompt[0] = (String) req.get("prompt");
                byte[] resp = objectMapper.writeValueAsBytes(Map.of("embedding", List.of(0.1)));
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        client.embed("hello\\nworld\\there\\r");

        assertNotNull(capturedPrompt[0]);
        assertFalse(capturedPrompt[0].contains("\\n"), "literal \\n should be normalised");
        assertFalse(capturedPrompt[0].contains("\\t"), "literal \\t should be normalised");
        assertFalse(capturedPrompt[0].contains("\\r"), "literal \\r should be normalised");
    }

    @Test
    void embedTruncatesTextTo3000Chars() throws Exception {
        String[] capturedPrompt = {null};
        server.createContext("/api/embeddings", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<?, ?> req = objectMapper.readValue(body, Map.class);
                capturedPrompt[0] = (String) req.get("prompt");
                byte[] resp = objectMapper.writeValueAsBytes(Map.of("embedding", List.of(0.1)));
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        client.embed("a".repeat(5_000));

        assertNotNull(capturedPrompt[0]);
        assertTrue(capturedPrompt[0].length() <= 3_000);
    }

    @Test
    void isBlankAfterNormalisationTrueForEmptyAndWhitespaceOnly() {
        assertTrue(OllamaEmbeddingClient.isBlankAfterNormalisation(""));
        assertTrue(OllamaEmbeddingClient.isBlankAfterNormalisation(" "));
        assertTrue(OllamaEmbeddingClient.isBlankAfterNormalisation("\n"));
        assertTrue(OllamaEmbeddingClient.isBlankAfterNormalisation("\\n\\n"));
        assertTrue(OllamaEmbeddingClient.isBlankAfterNormalisation("\t \r"));
    }

    @Test
    void isBlankAfterNormalisationFalseForRealContent() {
        assertFalse(OllamaEmbeddingClient.isBlankAfterNormalisation("a"));
        assertFalse(OllamaEmbeddingClient.isBlankAfterNormalisation(" a "));
        assertFalse(OllamaEmbeddingClient.isBlankAfterNormalisation("æ"));
    }
}
