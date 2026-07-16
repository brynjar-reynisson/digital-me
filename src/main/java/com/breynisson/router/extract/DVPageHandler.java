package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class DVPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("dv.is");
    }

    @Override
    public String extract(Document doc) {
        Element articleBody = doc.selectFirst("div.article-body");
        if (articleBody == null) {
            return null;
        }
        String paragraphs = doc.select("div.article-body .field--name-body > p").text();
        Element headline = doc.selectFirst("h1");
        if (headline == null) {
            return paragraphs;
        }
        return headline.text() + "\n\n" + paragraphs;
    }
}
