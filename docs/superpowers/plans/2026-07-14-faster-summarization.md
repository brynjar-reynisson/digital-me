# Faster Summarization (DeepSeek via opencode) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `SummarizeClient` implementation that shells out to the `opencode` CLI to summarize via DeepSeek, and make it the default summarization backend (config-selectable back to Ollama).

**Architecture:** New `DeepseekSummarizeClient` (package `com.breynisson.router.mcp`) implements the existing `SummarizeClient` interface by invoking `opencode run --model <model> --format json "<prompt>"` as a subprocess and parsing its newline-delimited JSON stdout. A new `summarize.provider` property (`deepseek` default, `ollama` alternative), applied via `@ConditionalOnProperty` on both `DeepseekSummarizeClient` and the existing `OllamaSummarizeClient`, selects which bean Spring wires into `SemanticSearch`/`IndexPage`. No other component changes — embeddings/semantic search keep using Ollama.

**Tech Stack:** Java 19, Spring Boot 3.3.11 (`@ConditionalOnProperty` from `spring-boot-autoconfigure`), Jackson `ObjectMapper` (existing bean), JUnit 5.

## Global Constraints

- Branch: `feature/faster_summarization` (global CLAUDE.md: feature branches use the `feature/` prefix).
- `mvn` is not on PATH; use: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" <goals>` (per `docs/tooling.md`).
- Checkstyle runs automatically via a PostToolUse hook on every Java file edit (`docs/tooling.md`) — no unused imports, no `EmptyCatchBlock` without `ignored`/`expected` variable name, etc.
- Per `CLAUDE.md`, run `/simplify` after changing source files.
- Do not remove or change `OllamaSummarizeClient`'s existing behavior beyond adding the `@ConditionalOnProperty` annotation — it must remain fully usable via `summarize.provider=ollama`.
- No real `opencode`/DeepSeek process is invoked in automated tests (per approved design spec) — only the pure NDJSON-parsing method is unit-tested.

---

## Setup

- [ ] Create and switch to the feature branch:

```bash
git -C "C:/Users/Lenovo/IdeaProjects/digital-me" checkout -b feature/faster_summarization
```

---

### Task 1: `DeepseekSummarizeClient` with NDJSON parsing

**Files:**
- Create: `src/main/java/com/breynisson/router/mcp/DeepseekSummarizeClient.java`
- Create: `src/test/java/com/breynisson/router/mcp/DeepseekSummarizeClientTest.java`

**Interfaces:**
- Consumes: `com.breynisson.router.mcp.SummarizeClient` (existing interface: `String summarize(String text)`, `default boolean isAvailable()`).
- Produces: `DeepseekSummarizeClient` (public class, `@Component`, implements `SummarizeClient`); package-visible static method `static String extractSummary(String stdout, com.fasterxml.jackson.databind.ObjectMapper objectMapper)` used by later tasks' manual verification and by this task's own tests.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/mcp/DeepseekSummarizeClientTest.java`:

```java
package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepseekSummarizeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsSummaryFromSingleTextLine() {
        String ndjson = """
                {"type":"step_start","part":{"type":"step-start"}}
                {"type":"text","part":{"type":"text","text":"A short summary."}}
                {"type":"step_finish","part":{"type":"step-finish"}}
                """;
        assertEquals("A short summary.", DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }

    @Test
    void lastTextLineWinsWhenMultiplePresent() {
        String ndjson = """
                {"type":"text","part":{"type":"text","text":"draft one"}}
                {"type":"text","part":{"type":"text","text":"final summary"}}
                """;
        assertEquals("final summary", DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }

    @Test
    void malformedLineIsIgnored() {
        String ndjson = """
                not json at all
                {"type":"text","part":{"type":"text","text":"still works"}}
                """;
        assertEquals("still works", DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }

    @Test
    void returnsNullWhenNoTextLinePresent() {
        String ndjson = """
                {"type":"step_start","part":{"type":"step-start"}}
                {"type":"step_finish","part":{"type":"step-finish"}}
                """;
        assertNull(DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=DeepseekSummarizeClientTest`
Expected: Build failure — `cannot find symbol: class DeepseekSummarizeClient`.

- [ ] **Step 3: Create `DeepseekSummarizeClient`**

Create `src/main/java/com/breynisson/router/mcp/DeepseekSummarizeClient.java`:

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

/**
 * Calls the {@code opencode} CLI (routed to DeepSeek) to produce a short summary.
 * Returns {@code null} (and logs a warning) if opencode is not available or times out.
 */
@Component
@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "deepseek", matchIfMissing = true)
public class DeepseekSummarizeClient implements SummarizeClient {

    private static final Logger log = LoggerFactory.getLogger(DeepseekSummarizeClient.class);

    private final String opencodeCommand;
    private final String model;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;

    public DeepseekSummarizeClient(
            @Value("${opencode.command:opencode}") String opencodeCommand,
            @Value("${opencode.summarize.model:deepseek/deepseek-v4-flash}") String model,
            @Value("${opencode.summarize.timeout-seconds:60}") long timeoutSeconds,
            ObjectMapper objectMapper) {
        this.opencodeCommand = opencodeCommand;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    @Override
    public String summarize(String text) {
        String prompt = "Summarize the following in 2-3 sentences:\n\n" + text;
        try {
            Process process = new ProcessBuilder(opencodeCommand, "run", "--model", model, "--format", "json", prompt)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("opencode summarize timed out after {}s", timeoutSeconds);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("opencode summarize exited with {}: {}", process.exitValue(), output);
                return null;
            }
            return extractSummary(output, objectMapper);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("opencode summarize unavailable: {}",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder(opencodeCommand, "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Parses opencode's `--format json` NDJSON stdout, returning the last "text" part's content, or null if none. */
    static String extractSummary(String stdout, ObjectMapper objectMapper) {
        String result = null;
        for (String rawLine : stdout.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                if ("text".equals(node.path("type").asText())) {
                    JsonNode textNode = node.path("part").path("text");
                    if (!textNode.isMissingNode()) {
                        result = textNode.asText().strip();
                    }
                }
            } catch (IOException ignored) {
                // not a JSON line (e.g. stray CLI output); skip it
            }
        }
        return result;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test -Dtest=DeepseekSummarizeClientTest`
Expected: `BUILD SUCCESS`, all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/breynisson/router/mcp/DeepseekSummarizeClient.java src/test/java/com/breynisson/router/mcp/DeepseekSummarizeClientTest.java
git commit -m "feat: add DeepseekSummarizeClient using opencode CLI"
```

---

### Task 2: Provider selection wiring and config

**Files:**
- Modify: `src/main/java/com/breynisson/router/mcp/OllamaSummarizeClient.java:20-21` (imports and class annotation)
- Modify: `src/main/resources/application.properties` (append new commented config block)

**Interfaces:**
- Consumes: `DeepseekSummarizeClient` (Task 1, already annotated `@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "deepseek", matchIfMissing = true)`).
- Produces: exactly one `SummarizeClient` Spring bean at a time, selected by the `summarize.provider` property, consumed unchanged by `SemanticSearch` and `IndexPage` constructors.

- [ ] **Step 1: Add `@ConditionalOnProperty` to `OllamaSummarizeClient`**

In `src/main/java/com/breynisson/router/mcp/OllamaSummarizeClient.java`, add a new import line right after the existing `import org.springframework.beans.factory.annotation.Value;` line:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

Then replace the existing:

```java
@Component
public class OllamaSummarizeClient implements SummarizeClient {
```

with:

```java
@Component
@ConditionalOnProperty(prefix = "summarize", name = "provider", havingValue = "ollama")
public class OllamaSummarizeClient implements SummarizeClient {
```

- [ ] **Step 2: Add config properties**

Append to `src/main/resources/application.properties` (after the existing `# semantic-search.min-score=0.5` line):

```properties

# Summarization backend: "deepseek" (default, via opencode CLI) or "ollama"
# summarize.provider=deepseek
# opencode.command=opencode
# opencode.summarize.model=deepseek/deepseek-v4-flash
# opencode.summarize.timeout-seconds=60
```

- [ ] **Step 3: Run the full test suite to confirm nothing broke**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q test`
Expected: `BUILD SUCCESS`, no test failures (in particular, Spring context tests that construct `IndexPage`/`SemanticSearch` must still resolve exactly one `SummarizeClient` bean via the `matchIfMissing = true` default).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/breynisson/router/mcp/OllamaSummarizeClient.java src/main/resources/application.properties
git commit -m "feat: make summarization backend config-selectable (deepseek default)"
```

---

### Task 3: Documentation

**Files:**
- Modify: `docs/architecture.md` (the `### SummarizeClient` and `### OllamaSummarizeClient` bullets, in the "Key subsystems" section)

**Interfaces:**
- Consumes: nothing (docs only).
- Produces: nothing (docs only).

- [ ] **Step 1: Update `docs/architecture.md`**

Locate the existing bullets:
```
### `SummarizeClient` (functional interface)
- Single method: `String summarize(String text)` — returns `null` when Ollama is unavailable
- Used as a lambda in tests; `OllamaSummarizeClient` is the production implementation

### `OllamaSummarizeClient`
- Posts to `http://localhost:11434/api/generate` with model configurable via `ollama.summarize.model` (default: `llama3.2`)
- Sends a "Summarize in 2-3 sentences" prompt; 120-second timeout
- Returns `null` on HTTP error or connection failure
```

Replace with:
```
### `SummarizeClient` (functional interface)
- Single method: `String summarize(String text)` — returns `null` when the backend is unavailable
- Used as a lambda in tests; two production implementations exist, selected via `summarize.provider` (`deepseek` default, `matchIfMissing = true`; or `ollama`), each `@ConditionalOnProperty`-gated so exactly one is registered as a Spring bean

### `DeepseekSummarizeClient`
- Default summarization backend; shells out to the `opencode` CLI: `opencode run --model <model> --format json "<prompt>"` (model configurable via `opencode.summarize.model`, default `deepseek/deepseek-v4-flash`)
- Sends the same "Summarize in 2-3 sentences" prompt as `OllamaSummarizeClient`
- Parses the CLI's newline-delimited JSON stdout via the static `extractSummary()` method, taking the last `"type":"text"` event's `part.text`
- Times out after `opencode.summarize.timeout-seconds` (default 60s), destroying the process and returning `null`
- `isAvailable()` runs `opencode --version` and checks for a zero exit code

### `OllamaSummarizeClient`
- Alternate summarization backend, enabled via `summarize.provider=ollama`
- Posts to `http://localhost:11434/api/generate` with model configurable via `ollama.summarize.model` (default: `llama3.2`)
- Sends a "Summarize in 2-3 sentences" prompt; 120-second timeout
- Returns `null` on HTTP error or connection failure
```

- [ ] **Step 2: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: document DeepseekSummarizeClient and summarize.provider toggle"
```

---

### Task 4: Manual end-to-end verification

**Files:** none (verification only, no code changes)

**Interfaces:** none

- [ ] **Step 1: Build and start the app**

From the project root (working directory must be `digital-me-dev/` per `docs/architecture.md`'s Camel routes note):

```bash
cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" -q package -DskipTests
```

then run the jar with `digital-me-dev/` as the working directory (see project's `start.cmd` for the exact existing launch invocation).

- [ ] **Step 2: Call `/summarize` with real text**

```bash
curl -s -X POST http://localhost:8080/summarize -H "Content-Type: application/json" -d "{\"text\":\"The quick brown fox jumps over the lazy dog. This pangram sentence contains every letter of the English alphabet at least once, and has been used for over a century to test typewriters, fonts, and keyboards.\"}"
```

Expected: JSON response `{"summary":"..."}` with a non-empty, coherent 2-3 sentence summary — confirming the real `opencode`/DeepSeek round trip works end-to-end (this is the one point in the whole plan where the real CLI/API is exercised, deliberately outside the automated test suite per the approved design).

- [ ] **Step 3: Confirm the health endpoint reflects availability**

```bash
curl -s http://localhost:8080/health/ollama
```

Expected: `"summarize":true` in the JSON response.

- [ ] **Step 4: Stop the app**

Use the project's existing `stop.cmd`, or stop the process you started in Step 1.

No commit for this task — it's verification only.
