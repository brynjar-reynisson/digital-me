package com.breynisson.router.extract;

import org.jsoup.nodes.Document;

/**
 * wumo.com strips are indexed by scripts/comic-index.py (OCR text plus a scene description). A
 * browser capture of the same page is just site chrome (navigation, comments, newsletter), and
 * since it shares the strip's source URL it would replace that description. So page captures are
 * discarded, and only the indexer's own payloads, recognisable by their header, pass through.
 */
public class WumoPageHandler implements PageHandler {

    static final String INDEXER_HEADER = "Wumo cartoon ";

    @Override
    public boolean matches(String url) {
        return url.contains("wumo.com");
    }

    @Override
    public String extract(Document doc) {
        String text = doc.body().text();
        return text.startsWith(INDEXER_HEADER) ? text : null;
    }

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return false;
    }

    @Override
    public String siteName() {
        return "wumo";
    }
}
