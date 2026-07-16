package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class VisirPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("visir.is");
    }

    @Override
    public String extract(Document doc) {
        Element body = doc.selectFirst("div[itemprop=articleBody]");
        if (body == null) {
            return null;
        }
        Element headline = doc.selectFirst("h1");
        if (headline == null) {
            return body.text();
        }
        return headline.text() + "\n\n" + body.text();
    }

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return url.contains("/g/");
    }

    @Override
    public String siteName() {
        return "visir";
    }
}
