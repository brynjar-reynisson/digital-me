# Gemini Fast Summarization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Google's free Gemini API the default, fast summarization backend for `/summarize`, falling back to the existing DeepSeek/opencode path only when Gemini fails, times out, or is rate-limited.

**Architecture:** Two new plain (non-Spring) classes — `GeminiSummarizeClient` (HTTP client for Gemini's `generateContent` endpoint) and `FallbackSummarizeClient` (a generic primary/fallback `SummarizeClient` composite) — plus a refactor of the existing `DeepseekSummarizeClient`/`OllamaSummarizeClient` from self-registering `@Component`s into plain classes constructed by a single new `AppConfig.summarizeClient()` `@Bean` factory method, which picks the wiring based on `summarize.provider`.

**Tech Stack:** Java 19, Spring Boot 3.3.11, Jackson `ObjectMapper`, `java.net.http.HttpClient`, JUnit 5, `com.sun.net.httpserver.HttpServer` (test-only HTTP mocking).

**Spec:** `docs/superpowers/specs/2026-09-05-gemini-fast-summarize-design.md`

## Global Constraints

- Only the summarization path (`SummarizeClient` → `/summarize`) changes — embeddings/semantic search continue using Ollama (`nomic-embed-text`) unchanged.
- No text-length-based routing cutoff — routing between Gemini and DeepSeek is purely success/failure based, never based on input size.
- `OllamaSummarizeClient` remains available and unchanged in behavior, selectable via `summarize.provider=ollama`.
- No MCP-facing changes — `/summarize` is REST-only, not an MCP tool.
- No retry/backoff inside `GeminiSummarizeClient` beyond the single fallback hop to DeepSeek.
- The Gemini API key must never appear in a URL, query string, or log line — always sent via the `x-goog-api-key` header.
- Exactly one `SummarizeClient` Spring bean must exist in the application context at all times (no ambiguous injection into `SemanticSearch`/`IndexPage`).

---

### Task 1: `FallbackSummarizeClient`

**Files:**
- Create: `src/main/java/com/breynisson/router/mcp/FallbackSummarizeClient.java`
- Test: `src/test/java/com/breynisson/router/mcp/FallbackSummarizeClientTest.java`

**Interfaces:**
- Consumes: `com.breynisson.router.mcp.SummarizeClient` (existing interface — `String summarize(String text)`, `default boolean isAvailable() { return true; }`)
- Produces: `public class FallbackSummarizeClient implements SummarizeClient` with constructor `FallbackSummarizeClient(SummarizeClient primary, SummarizeClient fallback)`. Task 4 constructs this directly with `new`; it is never itself a Spring bean.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/mcp/FallbackSummarizeClientTest.java`:

```java
package com.breynisson.router.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallbackSummarizeClientTest {

    @Test
    void primarySuccessIsReturnedWithoutCallingFallback() {
        SummarizeClient primary = text -> "primary summary";
        SummarizeClient fallback = text -> { throw new AssertionError("fallback should not be called"); };

        FallbackSummarizeClient client = new FallbackSummarizeClient(primary, fallback);

        assertEquals("primary summary", client.summarize("some text"));
    }

    @Test
    void primaryNullFallsBackToSecondary() {
        SummarizeClient primary = text -> null;
        SummarizeClient fallback = text -> "fallback summary";

        FallbackSummarizeClient client = new FallbackSummarizeClient(primary, fallback);

        assertEquals("fallback summary", client.summarize("some text"));
    }

    @Test
    void bothNullReturnsNull() {
        SummarizeClient primary = text -> null;
        SummarizeClient fallback = text -> null;

        FallbackSummarizeClient client = new FallbackSummarizeClient(primary, fallback);

        assertNull(client.summarize("some text"));
    }

    @Test
    void isAvailableTrueWhenOnlyPrimaryAvailable() {
        SummarizeClient primary = availableClient(true);
        SummarizeClient fallback = availableClient(false);

        assertTrue(new FallbackSummarizeClient(primary, fallback).isAvailable());
    }

    @Test
    void isAvailableTrueWhenOnlyFallbackAvailable() {
        SummarizeClient primary = availableClient(false);
        SummarizeClient fallback = availableClient(true);

        assertTrue(new FallbackSummarizeClient(primary, fallback).isAvailable());
    }

    @Test
    void isAvailableFalseWhenNeitherAvailable() {
        SummarizeClient primary = availableClient(false);
        SummarizeClient fallback = availableClient(false);

        assertFalse(new FallbackSummarizeClient(primary, fallback).isAvailable());
    }

    private static SummarizeClient availableClient(boolean available) {
        return new SummarizeClient() {
            @Override
            public String summarize(String text) {
                return null;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }
        };
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dtest=FallbackSummarizeClientTest test
```
Expected: FAIL to compile — `FallbackSummarizeClient` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/mcp/FallbackSummarizeClient.java`:

```java
package com.breynisson.router.mcp;

/**
 * Tries {@code primary} first; falls back to {@code fallback} only when the primary
 * returns {@code null} (unavailable, errored, timed out, or rate-limited).
 */
public class FallbackSummarizeClient implements SummarizeClient {

    private final SummarizeClient primary;
    private final SummarizeClient fallback;

    public FallbackSummarizeClient(SummarizeClient primary, SummarizeClient fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String summarize(String text) {
        String result = primary.summarize(text);
        return result != null ? result : fallback.summarize(text);
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || fallback.isAvailable();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dtest=FallbackSummarizeClientTest test
```
Expected: PASS, all 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/mcp/FallbackSummarizeClient.java src/test/java/com/breynisson/router/mcp/FallbackSummarizeClientTest.java
git commit -m "feat: add FallbackSummarizeClient primary/fallback composite

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015v3KrCCmXCWcn62KnKtNEA"
```

---

### Task 2: `GeminiSummarizeClient`

**Files:**
- Create: `src/main/java/com/breynisson/router/mcp/GeminiSummarizeClient.java`
- Test: `src/test/java/com/breynisson/router/mcp/GeminiSummarizeClientTest.java`

**Interfaces:**
- Consumes: `com.breynisson.router.mcp.SummarizeClient`; `com.fasterxml.jackson.databind.ObjectMapper` (existing Spring-managed bean, passed in by the caller — not looked up by this class).
- Produces: `public class GeminiSummarizeClient implements SummarizeClient` with constructor `GeminiSummarizeClient(String baseUrl, String apiKey, String model, long timeoutSeconds, ObjectMapper objectMapper)`. `baseUrl` has no default inside this class (Task 4 supplies `https://generativelanguage.googleapis.com` in production; tests point it at a local mock server) — this is what makes the class testable without a real Gemini endpoint, the same reason `OllamaSummarizeClient` takes an injectable `ollamaUrl`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/mcp/GeminiSummarizeClientTest.java`:

```java
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dtest=GeminiSummarizeClientTest test
```
Expected: FAIL to compile — `GeminiSummarizeClient` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/mcp/GeminiSummarizeClient.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dtest=GeminiSummarizeClientTest test
```
Expected: PASS, all 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/mcp/GeminiSummarizeClient.java src/test/java/com/breynisson/router/mcp/GeminiSummarizeClientTest.java
git commit -m "feat: add GeminiSummarizeClient for the free-tier Gemini generateContent API

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015v3KrCCmXCWcn62KnKtNEA"
```

---

### Task 3: Refactor `DeepseekSummarizeClient` and `OllamaSummarizeClient` into plain classes

**Files:**
- Modify: `src/main/java/com/breynisson/router/mcp/DeepseekSummarizeClient.java:1-48`
- Modify: `src/main/java/com/breynisson/router/mcp/OllamaSummarizeClient.java:1-41`

**Interfaces:**
- Consumes: nothing new.
- Produces: `DeepseekSummarizeClient(String opencodeCommand, String model, long timeoutSeconds, ObjectMapper objectMapper)` and `OllamaSummarizeClient(String ollamaUrl, String model, ObjectMapper objectMapper)` — same parameter order/types as today, just no longer Spring-annotated. Task 4's `AppConfig.summarizeClient()` constructs both directly with `new`.

This task removes Spring self-registration (`@Component` + `@ConditionalOnProperty` + `@Value`) from both classes so they become plain classes that `AppConfig` wires up explicitly — required because the new default path needs to compose two `SummarizeClient`s together (`GeminiSummarizeClient` + `DeepseekSummarizeClient`), and Spring can't have two active `@Component` beans of the same interface without an ambiguous-injection error. No runtime behavior changes in either class.

- [ ] **Step 1: Run the existing test suite to confirm the baseline passes**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q -Dtest=DeepseekSummarizeClientTest test
```
Expected: PASS (this test only exercises the static `extractSummary`/`sanitizeArgument` helpers, so it is unaffected by this refactor and should already be green).

- [ ] **Step 2: Strip Spring annotations from `DeepseekSummarizeClient`**

In `src/main/java/com/breynisson/router/mcp/DeepseekSummarizeClient.java`, replace:

```java
package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Calls the {@code opencode} CLI (routed to DeepSeek) to produce a short summary.
 * Returns {@code null} (and logs a warning) if opencode is not available or times out.
 */
@Component
@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "deepseek", matchIfMissing = true)
public class DeepseekSummarizeClient implements SummarizeClient {
```

with:

```java
package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Calls the {@code opencode} CLI (routed to DeepSeek) to produce a short summary.
 * Returns {@code null} (and logs a warning) if opencode is not available or times out.
 */
public class DeepseekSummarizeClient implements SummarizeClient {
```

Then replace the constructor:

```java
    // Windows-installed npm CLIs are .cmd shims; ProcessBuilder does not do PATHEXT-style
    // resolution of bare command names the way cmd.exe does, so the extension is required.
    public DeepseekSummarizeClient(
            @Value("${opencode.command:opencode.cmd}") String opencodeCommand,
            @Value("${opencode.summarize.model:deepseek/deepseek-v4-flash}") String model,
            @Value("${opencode.summarize.timeout-seconds:60}") long timeoutSeconds,
            ObjectMapper objectMapper) {
```

with:

```java
    // Windows-installed npm CLIs are .cmd shims; ProcessBuilder does not do PATHEXT-style
    // resolution of bare command names the way cmd.exe does, so the extension is required.
    public DeepseekSummarizeClient(
            String opencodeCommand,
            String model,
            long timeoutSeconds,
            ObjectMapper objectMapper) {
```

- [ ] **Step 3: Strip Spring annotations from `OllamaSummarizeClient`**

In `src/main/java/com/breynisson/router/mcp/OllamaSummarizeClient.java`, replace:

```java
package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
@Component
@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "ollama")
public class OllamaSummarizeClient implements SummarizeClient {
```

with:

```java
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
```

Then replace the constructor:

```java
    public OllamaSummarizeClient(
            @Value("${ollama.url:http://localhost:11434}") String ollamaUrl,
            @Value("${ollama.summarize.model:llama3.2}") String model,
            ObjectMapper objectMapper) {
```

with:

```java
    public OllamaSummarizeClient(
            String ollamaUrl,
            String model,
            ObjectMapper objectMapper) {
```

- [ ] **Step 4: Compile and run the full existing test suite**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test
```
Expected: Build FAILS at this point with a Spring context error — nothing constructs `DeepseekSummarizeClient`/`OllamaSummarizeClient` anymore since their self-registration is gone and `AppConfig` doesn't yet provide a `SummarizeClient` bean (that's Task 4). Confirm the failure is specifically a missing/unsatisfied `SummarizeClient` dependency (in `SemanticSearch`/`IndexPage`), not a compile error in the two files just edited — a compile error there would mean Step 2 or 3 was applied incorrectly.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/mcp/DeepseekSummarizeClient.java src/main/java/com/breynisson/router/mcp/OllamaSummarizeClient.java
git commit -m "refactor: make DeepseekSummarizeClient and OllamaSummarizeClient plain classes

No longer self-register as Spring beans, since the default summarize path
now needs to compose two SummarizeClient implementations together. Wiring
moves to AppConfig in the next commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015v3KrCCmXCWcn62KnKtNEA"
```

(The app intentionally does not build a runnable `SummarizeClient` bean between this commit and Task 4's — that's expected and corrected by the very next task.)

---

### Task 4: Wire `AppConfig.summarizeClient()`, update configuration and docs

**Files:**
- Modify: `src/main/java/com/breynisson/router/AppConfig.java`
- Modify: `src/main/resources/application.properties`
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes: `FallbackSummarizeClient(SummarizeClient, SummarizeClient)` (Task 1), `GeminiSummarizeClient(String, String, String, long, ObjectMapper)` (Task 2), `DeepseekSummarizeClient(String, String, long, ObjectMapper)` and `OllamaSummarizeClient(String, String, ObjectMapper)` (Task 3).
- Produces: a single `@Bean SummarizeClient summarizeClient(...)` — the only `SummarizeClient` bean in the application context, consumed unchanged by `SemanticSearch` and `IndexPage`.

- [ ] **Step 1: Add the `summarizeClient` bean to `AppConfig`**

In `src/main/java/com/breynisson/router/AppConfig.java`, add these imports alongside the existing ones:

```java
import com.breynisson.router.mcp.DeepseekSummarizeClient;
import com.breynisson.router.mcp.FallbackSummarizeClient;
import com.breynisson.router.mcp.GeminiSummarizeClient;
import com.breynisson.router.mcp.OllamaSummarizeClient;
import com.breynisson.router.mcp.SummarizeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
```

Then add this bean method (after the existing `contentReceive` bean, before the closing brace of the class):

```java
    @Bean
    public SummarizeClient summarizeClient(
            ObjectMapper objectMapper,
            @Value("${summarize.provider:gemini}") String provider,
            @Value("${opencode.command:opencode.cmd}") String opencodeCommand,
            @Value("${opencode.summarize.model:deepseek/deepseek-v4-flash}") String deepseekModel,
            @Value("${opencode.summarize.timeout-seconds:60}") long deepseekTimeoutSeconds,
            @Value("${ollama.url:http://localhost:11434}") String ollamaUrl,
            @Value("${ollama.summarize.model:llama3.2}") String ollamaModel,
            @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}") String geminiBaseUrl,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.summarize.model:gemini-2.5-flash-lite}") String geminiModel,
            @Value("${gemini.summarize.timeout-seconds:20}") long geminiTimeoutSeconds) {
        DeepseekSummarizeClient deepseek = new DeepseekSummarizeClient(opencodeCommand, deepseekModel, deepseekTimeoutSeconds, objectMapper);
        return switch (provider) {
            case "ollama" -> new OllamaSummarizeClient(ollamaUrl, ollamaModel, objectMapper);
            case "deepseek" -> deepseek;
            default -> new FallbackSummarizeClient(
                    new GeminiSummarizeClient(geminiBaseUrl, geminiApiKey, geminiModel, geminiTimeoutSeconds, objectMapper),
                    deepseek);
        };
    }
```

- [ ] **Step 2: Add Gemini configuration to `application.properties`**

In `src/main/resources/application.properties`, replace:

```properties
# Summarization backend: must be exactly "deepseek" (default, via opencode CLI) or "ollama" —
# any other value leaves no SummarizeClient bean active and fails startup
# summarize.provider=deepseek
# opencode.command=opencode.cmd
# opencode.summarize.model=deepseek/deepseek-v4-flash
# opencode.summarize.timeout-seconds=60
```

with:

```properties
# Summarization backend: "gemini" (default) tries Gemini first, falling back to DeepSeek
# on any Gemini failure/timeout/rate-limit; "deepseek" or "ollama" use only that backend.
# summarize.provider=gemini

# Gemini free-tier API key. Resolves GEMINI_API_KEY first, falling back to an existing
# GOOGLE_API_KEY (e.g. from another project) if you already have one set — if that key
# isn't valid for the Generative Language API, calls simply fail and fall back to
# DeepSeek, same as Gemini being unavailable. Get a free key at aistudio.google.com/apikey.
gemini.api.key=${GEMINI_API_KEY:${GOOGLE_API_KEY:}}
# gemini.summarize.model=gemini-2.5-flash-lite
# gemini.summarize.timeout-seconds=20
# gemini.api.base-url=https://generativelanguage.googleapis.com

# opencode.command=opencode.cmd
# opencode.summarize.model=deepseek/deepseek-v4-flash
# opencode.summarize.timeout-seconds=60
```

- [ ] **Step 3: Compile and run the full test suite**

Run:
```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test
```
Expected: PASS — the Spring context now has exactly one `SummarizeClient` bean again, and every test from Tasks 1-3 plus the pre-existing suite is green.

- [ ] **Step 4: Update `docs/architecture.md`**

In `docs/architecture.md`, under `## Key subsystems`, replace:

```markdown
### `SummarizeClient` (functional interface)
- Single method: `String summarize(String text)` — returns `null` when the backend is unavailable
- Used as a lambda in tests; two production implementations exist, selected via `summarize.provider` (`deepseek` default, `matchIfMissing = true`; or `ollama`), each `@ConditionalOnProperty`-gated so exactly one is registered as a Spring bean

### `DeepseekSummarizeClient`
- Default summarization backend; shells out to the `opencode` CLI: `opencode run --model <model> --format json "<prompt>"` (model configurable via `opencode.summarize.model`, default `deepseek/deepseek-v4-flash`)
```

with:

```markdown
### `SummarizeClient` (functional interface)
- Single method: `String summarize(String text)` — returns `null` when the backend is unavailable
- Used as a lambda in tests; production implementations (`GeminiSummarizeClient`, `DeepseekSummarizeClient`, `OllamaSummarizeClient`, `FallbackSummarizeClient`) are plain classes with no Spring annotations, wired up by `AppConfig.summarizeClient()`, which reads `summarize.provider` (`gemini` default) and constructs: `gemini` → `FallbackSummarizeClient` wrapping `GeminiSummarizeClient` (primary) and `DeepseekSummarizeClient` (fallback); `deepseek` → `DeepseekSummarizeClient` alone; `ollama` → `OllamaSummarizeClient` alone. Exactly one `SummarizeClient` bean exists in the context either way

### `GeminiSummarizeClient`
- Default fast summarization backend; posts to Google's free-tier Gemini API (`{gemini.api.base-url}/v1beta/models/{model}:generateContent`, model configurable via `gemini.summarize.model`, default `gemini-2.5-flash-lite`), with the API key sent via the `x-goog-api-key` header rather than the URL, so it never lands in logs
- `gemini.api.key` resolves the `GEMINI_API_KEY` env var first, falling back to an existing `GOOGLE_API_KEY` (e.g. from another project) if set — an incompatible key simply causes calls to fail and fall back to DeepSeek, exactly as if no key were configured at all
- Returns `null` immediately (no HTTP call made) when the resolved API key is blank, and also on any non-200 response (including a 429 rate-limit), a timeout (`gemini.summarize.timeout-seconds`, default 20s), or a network failure
- `isAvailable()` does a lightweight `GET {gemini.api.base-url}/v1beta/models` reachability check rather than spending a full summarization call against the free tier's daily quota

### `FallbackSummarizeClient`
- Wraps a primary and a fallback `SummarizeClient`; `summarize()` tries the primary first and only calls the fallback when the primary returns `null`. `isAvailable()` is `primary.isAvailable() || fallback.isAvailable()`. Composes `GeminiSummarizeClient` (primary) with `DeepseekSummarizeClient` (fallback) to form the default `summarize.provider=gemini` behavior

### `DeepseekSummarizeClient`
- Fallback summarization backend by default (used directly only when `summarize.provider=deepseek`); shells out to the `opencode` CLI: `opencode run --model <model> --format json "<prompt>"` (model configurable via `opencode.summarize.model`, default `deepseek/deepseek-v4-flash`)
```

Leave the remaining `DeepseekSummarizeClient` bullets (opencode.command, sanitizeArgument, stdin handling, extractSummary, timeout, isAvailable) and the entire `OllamaSummarizeClient` section unchanged — only the two headings/intro bullets above change.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/AppConfig.java src/main/resources/application.properties docs/architecture.md
git commit -m "feat: wire Gemini as the default summarizer with DeepSeek fallback

AppConfig.summarizeClient() now composes GeminiSummarizeClient (primary)
with DeepseekSummarizeClient (fallback) by default via
FallbackSummarizeClient, selectable via summarize.provider=gemini
(default) / deepseek / ollama.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015v3KrCCmXCWcn62KnKtNEA"
```

- [ ] **Step 6: Manual end-to-end verification (not automated)**

Start the app (see `CLAUDE.md` build/run instructions) with a real `GEMINI_API_KEY` (or a working `GOOGLE_API_KEY`) set in the environment, and `summarize.provider` left at its default. Run a search in the UI, confirm `/summarize` returns a real Gemini-produced summary quickly (well under the old ~12-14s DeepSeek latency). Then unset/break the key and repeat — confirm a summary still comes back (slower, via the DeepSeek fallback), proving the fallback path works end to end. This step has no pass/fail assertion beyond your own observation — record what you saw when reporting this task done.
