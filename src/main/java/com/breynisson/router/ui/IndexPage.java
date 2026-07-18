package com.breynisson.router.ui;

import com.breynisson.router.digitalme.AddContentRequest;
import com.breynisson.router.digitalme.AddContentResponse;
import com.breynisson.router.digitalme.DigitalMeStorage;
import com.breynisson.router.digitalme.SearchResponse;
import com.breynisson.router.digitalme.SearchResult;
import com.breynisson.router.digitalme.SemanticSearch;
import com.breynisson.router.jdbc.McpEmbeddingDao;
import com.breynisson.router.jdbc.TextEntryDao;
import com.breynisson.router.mcp.EmbeddingClient;
import com.breynisson.router.mcp.SummarizeClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    record SummarizeRequest(String text, String source) {}
    record SummarizeResponse(String summary) {}

    @PostMapping(value = "/summarize", consumes = "application/json", produces = "application/json")
    public SummarizeResponse summarize(@RequestBody SummarizeRequest request) {
        String summary = semanticSearch.summarize(request.text(), request.source());
        return new SummarizeResponse(summary != null ? summary : "");
    }

    @GetMapping(value = "/localFile", produces = MediaType.TEXT_HTML_VALUE)
    public String localFile(@RequestParam String filePath) throws IOException {
        Path resolved = resolveFilePath(filePath);
        String content = Files.readString(resolved);
        if (MarkdownPageRenderer.isMarkdownFile(resolved.toString())) {
            return MarkdownPageRenderer.render(content);
        }
        content = HtmlUtils.htmlEscape(content);
        return "<html><body><p style='white-space: pre-wrap;'>" +
                content +
                "</p></body></html>";
    }

    private static final Pattern NON_LOCAL_SOURCE_SCHEME = Pattern.compile("^(?!https?://)[a-zA-Z][a-zA-Z0-9+.-]*://");

    /**
     * filePath is normally an actual filesystem path, but search results for synthetic sources
     * (e.g. claude:// Claude Code session URLs) put a non-path source identifier there instead;
     * resolve those via the source-URL index of the mcp-resources file that was indexed for them.
     *
     * filePath is caller-supplied (a raw query parameter), so for the plain-path case it must be
     * constrained to files this app already knows about: it's rejected outright if it looks like a
     * UNC/network path (Windows would otherwise open an SMB session to an attacker-controlled host
     * just to read it, leaking NTLM credentials) and otherwise must already be a TEXT_ENTRY the app
     * itself indexed, rather than trusting the path to point wherever the caller wants.
     */
    private static Path resolveFilePath(String filePath) {
        if (NON_LOCAL_SOURCE_SCHEME.matcher(filePath).find()) {
            Set<String> candidates = McpEmbeddingDao.findFilePathsBySourceUrl(filePath);
            if (!candidates.isEmpty()) {
                return Paths.get(candidates.iterator().next());
            }
        }
        if (filePath.startsWith("\\\\") || filePath.startsWith("//")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Network paths are not allowed");
        }
        if (TextEntryDao.findByName(filePath).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "File is not indexed");
        }
        return Paths.get(filePath);
    }

    @PostMapping(value="/addContent", consumes = "application/json", produces = "application/json")
    public AddContentResponse addContent(@RequestBody AddContentRequest addContentRequest) {
        return storage.addContent(addContentRequest);
    }
}
