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
