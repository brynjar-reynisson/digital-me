package com.breynisson.router;

import com.breynisson.router.digitalme.DefaultDigitalMeStorage;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.mcp.DeepseekSummarizeClient;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.FallbackSummarizeClient;
import com.breynisson.router.mcp.GeminiSummarizeClient;
import com.breynisson.router.mcp.OllamaSummarizeClient;
import com.breynisson.router.mcp.SummarizeClient;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.lucene.LuceneIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    private final String dataDir;

    public AppConfig(
            @Value("${data.dir:.}") String dataDir,
            @Value("${postgres.host:localhost}") String postgresHost,
            @Value("${postgres.port:54322}") int postgresPort,
            @Value("${postgres.database:postgres}") String postgresDatabase,
            @Value("${postgres.user:postgres}") String postgresUser,
            @Value("${postgres.password:postgres}") String postgresPassword,
            @Value("${postgres.schema:digitalme}") String postgresSchema) {
        this.dataDir = dataDir;
        DatabaseAdapter.configure(postgresHost, postgresPort, postgresDatabase, postgresUser, postgresPassword, postgresSchema);
        LuceneIndex.setIndexPath(dataDir + "/lucene-index");
        DatabaseAdapter.init();
    }

    @Bean
    public DigitalMeStorage digitalMeStorage(EmbeddingIndex embeddingIndex,
            @Value("${embedding.executor.pool-size:1}") int embeddingPoolSize) {
        return new DefaultDigitalMeStorage(dataDir, embeddingIndex, embeddingPoolSize);
    }

    @Bean
    public FileDeletion fileDeletion(CamelContext camelContext) {
        return new FileDeletion();
    }

    @Bean
    public FileCopy fileCopy(CamelContext camelContext) {
        return new FileCopy();
    }

    @Bean
    public FileChangeWatcher fileChangeWatcher(DigitalMeStorage digitalMeStorage) {
        return new FileChangeWatcher(digitalMeStorage);
    }

    @Bean
    public ContentReceive contentReceive(CamelContext camelContext) {
        return new ContentReceive(camelContext);
    }

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
            @Value("${gemini.summarize.model:gemini-3.5-flash-lite}") String geminiModel,
            @Value("${gemini.summarize.timeout-seconds:10}") long geminiTimeoutSeconds) {
        DeepseekSummarizeClient deepseek = new DeepseekSummarizeClient(opencodeCommand, deepseekModel, deepseekTimeoutSeconds, objectMapper);
        OllamaSummarizeClient ollama = new OllamaSummarizeClient(ollamaUrl, ollamaModel, objectMapper);
        GeminiSummarizeClient gemini = new GeminiSummarizeClient(geminiBaseUrl, geminiApiKey, geminiModel, geminiTimeoutSeconds, objectMapper);
        return buildSummarizeClient(provider, deepseek, gemini, ollama);
    }

    static SummarizeClient buildSummarizeClient(
            String provider, DeepseekSummarizeClient deepseek, GeminiSummarizeClient gemini, OllamaSummarizeClient ollama) {
        return switch (provider) {
            case "ollama" -> ollama;
            case "deepseek" -> deepseek;
            case "gemini" -> new FallbackSummarizeClient(gemini, deepseek);
            default -> throw new IllegalArgumentException(
                    "Unknown summarize.provider '" + provider + "' (expected gemini, deepseek, or ollama)");
        };
    }
}
