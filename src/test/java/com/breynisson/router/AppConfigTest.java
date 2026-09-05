package com.breynisson.router;

import com.breynisson.router.mcp.DeepseekSummarizeClient;
import com.breynisson.router.mcp.FallbackSummarizeClient;
import com.breynisson.router.mcp.GeminiSummarizeClient;
import com.breynisson.router.mcp.OllamaSummarizeClient;
import com.breynisson.router.mcp.SummarizeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeepseekSummarizeClient deepseek =
            new DeepseekSummarizeClient("opencode.cmd", "deepseek/deepseek-v4-flash", 60, objectMapper);
    private final GeminiSummarizeClient gemini =
            new GeminiSummarizeClient("http://localhost:0", "key", "gemini-2.5-flash-lite", 10, objectMapper);
    private final OllamaSummarizeClient ollama =
            new OllamaSummarizeClient("http://localhost:11434", "llama3.2", objectMapper);

    @Test
    void geminiProviderReturnsFallbackClient() {
        SummarizeClient result = AppConfig.buildSummarizeClient("gemini", deepseek, gemini, ollama);

        assertInstanceOf(FallbackSummarizeClient.class, result);
    }

    @Test
    void deepseekProviderReturnsTheDeepseekInstanceDirectly() {
        SummarizeClient result = AppConfig.buildSummarizeClient("deepseek", deepseek, gemini, ollama);

        assertSame(deepseek, result);
    }

    @Test
    void ollamaProviderReturnsTheOllamaInstanceDirectly() {
        SummarizeClient result = AppConfig.buildSummarizeClient("ollama", deepseek, gemini, ollama);

        assertSame(ollama, result);
    }

    @Test
    void unknownProviderThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AppConfig.buildSummarizeClient("Gemini", deepseek, gemini, ollama));

        assertTrue(ex.getMessage().contains("Gemini"));
    }
}
