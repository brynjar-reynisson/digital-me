package com.breynisson.router.ui;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentResponse;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.digitalme.SearchResponse;
import com.breynisson.router.digitalme.SearchResult;
import com.breynisson.router.digitalme.SemanticSearch;
import com.breynisson.router.mcp.EmbeddingClient;
import com.breynisson.router.mcp.SummarizeClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;

@RestController
public class IndexPage {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IndexPage.class);
    private final DigitalMeStorage storage;
    private final SemanticSearch semanticSearch;
    private final EmbeddingClient embeddingClient;
    private final SummarizeClient summarizeClient;

    public IndexPage(DigitalMeStorage storage, SemanticSearch semanticSearch, EmbeddingClient embeddingClient, SummarizeClient summarizeClient) {
        this.storage = storage;
        this.semanticSearch = semanticSearch;
        this.embeddingClient = embeddingClient;
        this.summarizeClient = summarizeClient;
    }

    @GetMapping("/")
    public RedirectView index() throws IOException, URISyntaxException {
        return new RedirectView("/index.html");
    }

    @GetMapping("/health/ollama")
    public Map<String, Object> ollamaHealth() {
        boolean embeddingAvailable = embeddingClient.isAvailable();
        boolean summarizeAvailable = summarizeClient.isAvailable();
        return Map.of(
            "online", embeddingAvailable || summarizeAvailable,
            "embedding", embeddingAvailable,
            "summarize", summarizeAvailable
        );
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam String keywords) {
        return storage.search(keywords);
    }

    @GetMapping("/semanticSearch")
    public SearchResponse semanticSearch(@RequestParam String keywords) {
        log.info("Semantic Search: {}", keywords);
        LinkedHashSet<SearchResult> results = new LinkedHashSet<>(semanticSearch.search(keywords));
        log.info("Semantic Search: {} found {} results", keywords, results.size());
        return new SearchResponse(results);
    }

    record SummarizeRequest(String text) {}
    record SummarizeResponse(String summary) {}

    @PostMapping(value = "/summarize", consumes = "application/json", produces = "application/json")
    public SummarizeResponse summarize(@RequestBody SummarizeRequest request) {
        String summary = semanticSearch.summarize(request.text());
        return new SummarizeResponse(summary != null ? summary : "");
    }

    @GetMapping(value = "/localFile", produces = MediaType.TEXT_HTML_VALUE)
    public String localFile(@RequestParam String filePath) throws IOException {
        String content = Files.readString(Paths.get(filePath));
        if (MarkdownPageRenderer.isMarkdownFile(filePath)) {
            return MarkdownPageRenderer.render(content);
        }
        content = HtmlUtils.htmlEscape(content);
        return "<html><body><p style='white-space: pre-wrap;'>" +
                content +
                "</p></body></html>";
    }

    @PostMapping(value="/addContent", consumes = "application/json", produces = "application/json")
    public AddContentResponse addContent(@RequestBody AddContentRequest addContentRequest) {
        return storage.addContent(addContentRequest);
    }
}
