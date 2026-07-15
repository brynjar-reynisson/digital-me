package com.breynisson.router.digitalme;

import com.breynisson.router.extract.PageHandler;
import com.breynisson.router.extract.PageHandlers;
import com.breynisson.router.extract.YouTubeCaptionExtractor;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.lucene.LuceneIndex;
import com.breynisson.router.mcp.EmbeddingIndex;
import com.breynisson.router.mcp.ResourceReceiver;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultDigitalMeStorage implements DigitalMeStorage {

    private static final Logger log = LoggerFactory.getLogger(DefaultDigitalMeStorage.class);

    private final Lock lock = new ReentrantLock();
    private final ResourceReceiver resourceReceiver;
    private final EmbeddingIndex embeddingIndex;

    public DefaultDigitalMeStorage(String dataDir, EmbeddingIndex embeddingIndex) {
        this.resourceReceiver = new ResourceReceiver(dataDir);
        this.embeddingIndex = embeddingIndex;
    }

    @Override
    public SearchResponse search(String keywords) {
        log.info("Search: {}", keywords);
        List<SearchResult> results = LuceneIndex.find(keywords);
        return new SearchResponse(new LinkedHashSet<>(results));
    }

    @Override
    public AddContentResponse addContent(AddContentRequest addContentRequest) {
        lock.lock();
        AddContentResponse contentResponse = new AddContentResponse();
        try {
            log.info("addContent: {}", addContentRequest.getSource());
            String content = addContentRequest.getContent();
            if (addContentRequest.getSource().startsWith("http")) {
                if (ScreenshotCoverage.isCovered(addContentRequest.getSource())) {
                    log.info("Discarding extension content already covered by screenshot capture: {}", addContentRequest.getSource());
                    contentResponse.setSuccess(true);
                    return contentResponse;
                }
                Optional<PageHandler> handler = PageHandlers.find(addContentRequest.getSource());
                if (handler.isPresent()) {
                    String extracted = handler.get().extract(Jsoup.parse(content));
                    if (extracted == null) {
                        log.info("Discarding content with no extractable body: {}", addContentRequest.getSource());
                        contentResponse.setSuccess(true);
                        return contentResponse;
                    }
                    content = normalize(extracted);
                } else if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
                    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
                } else {
                    content = normalize(Jsoup.parse(content).text());
                }
                addContentRequest.setContent(content);
            }
            Path written = resourceReceiver.addContent(addContentRequest);
            CompletableFuture.runAsync(() -> embeddingIndex.indexFile(written));
            LuceneIndex.createOrUpdateIndex(content, addContentRequest.getSource(), addContentRequest.getName());
            TextEntryDao.insertOrUpdate(addContentRequest.getSource());
            contentResponse.setSuccess(true);
        } catch (Exception e) {
            log.error("Error in addContent for {}", addContentRequest.getSource(), e);
            contentResponse.setSuccess(false);
            contentResponse.setErrorMessage(e.getMessage());
        } finally {
            lock.unlock();
        }
        return contentResponse;
    }

    private static String normalize(String content) {
        String normalized = content.replace("\\n", " ");
        normalized = normalized.replace("\\t", " ");
        normalized = normalized.replace("\\r", " ");
        return normalized.replaceAll("\\s+", " ").strip();
    }
}
