package com.breynisson.router.extract;

import org.jsoup.nodes.Document;

public interface PageHandler {

    boolean matches(String url);

    String extract(Document doc);

    boolean looksLikeArticleUrl(String url);

    String siteName();
}
