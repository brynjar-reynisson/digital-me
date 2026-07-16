package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Pattern;

public class DVPageHandler implements PageHandler {

    private static final Pattern ARTICLE_URL_PATTERN = Pattern.compile("/\\d+/\\d{4}/\\d{2}/\\d{2}/");

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

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return ARTICLE_URL_PATTERN.matcher(url).find();
    }

    @Override
    public String siteName() {
        return "dv";
    }
}
