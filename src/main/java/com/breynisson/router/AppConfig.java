package com.breynisson.router;

import com.breynisson.router.digitalme.DefaultDigitalMeStorage;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.jdbc.DatabaseAdapter;
import com.breynisson.router.lucene.LuceneIndex;
import org.apache.camel.CamelContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    private final String dataDir;

    public AppConfig(@Value("${data.dir:.}") String dataDir) {
        this.dataDir = dataDir;
        DatabaseAdapter.setDefaultDatabasePath(dataDir + "/digital-me.db");
        LuceneIndex.setIndexPath(dataDir + "/lucene-index");
        DatabaseAdapter.init();
    }

    @Bean
    public DigitalMeStorage digitalMeStorage(EmbeddingIndex embeddingIndex) {
        return new DefaultDigitalMeStorage(dataDir, embeddingIndex);
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
}
