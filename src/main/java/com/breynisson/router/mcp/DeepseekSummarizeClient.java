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

    private static final Logger log = LoggerFactory.getLogger(DeepseekSummarizeClient.class);

    // opencode.cmd runs through cmd.exe on Windows; text can originate from arbitrary
    // scraped web pages, so strip characters cmd.exe treats specially before they ever
    // reach the argument list (defense in depth, independent of JDK escaping fixes).
    private static final Pattern UNSAFE_ARGUMENT_CHARS = Pattern.compile("[&|<>^\"%\\r\\n]");

    private final String opencodeCommand;
    private final String model;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;

    // Windows-installed npm CLIs are .cmd shims; ProcessBuilder does not do PATHEXT-style
    // resolution of bare command names the way cmd.exe does, so the extension is required.
    public DeepseekSummarizeClient(
            @Value("${opencode.command:opencode.cmd}") String opencodeCommand,
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
        // No embedded newline here: this string becomes a single ProcessBuilder argument
        // routed through opencode.cmd, which Windows executes via cmd.exe /c — cmd.exe
        // truncates an argument at the first embedded newline.
        String prompt = "Summarize the following in 2-3 sentences: " + sanitizeArgument(text);
        try {
            Process process = new ProcessBuilder(opencodeCommand, "run", "--model", model, "--format", "json", prompt)
                    .redirectErrorStream(true)
                    .start();
            // opencode blocks reading stdin until it sees EOF; close it immediately since
            // the prompt is already passed as a CLI argument, not piped in.
            process.getOutputStream().close();
            AtomicReference<String> outputRef = new AtomicReference<>("");
            Thread reader = startOutputReader(process, outputRef);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("opencode summarize timed out after {}s", timeoutSeconds);
                return null;
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
            String output = outputRef.get();
            if (process.exitValue() != 0) {
                String truncatedOutput = output.length() > 500 ? output.substring(0, 500) + "..." : output;
                log.warn("opencode summarize exited with {}: {}", process.exitValue(), truncatedOutput);
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
            process.getOutputStream().close();
            AtomicReference<String> outputRef = new AtomicReference<>("");
            Thread reader = startOutputReader(process, outputRef);
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            reader.join(TimeUnit.SECONDS.toMillis(2));
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Thread startOutputReader(Process process, AtomicReference<String> outputRef) {
        Thread reader = new Thread(() -> {
            try {
                outputRef.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // process was destroyed or its stream closed abruptly; leave output as empty
            }
        });
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    /** Strips characters cmd.exe treats specially from untrusted text before it becomes a CLI argument. */
    static String sanitizeArgument(String text) {
        return UNSAFE_ARGUMENT_CHARS.matcher(text).replaceAll(" ");
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
