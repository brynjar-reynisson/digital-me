package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.regex.Pattern;

public class CNNPageHandler implements PageHandler {

    private static final Pattern ARTICLE_URL_PATTERN = Pattern.compile("/\\d{4}/\\d{2}/\\d{2}/");

    @Override
    public boolean matches(String url) {
        return url.contains("cnn.com");
    }

    @Override
    public String extract(Document doc) {
        Element articleBody = doc.selectFirst("div[itemprop=articleBody]");
        Elements paragraphs = articleBody != null
                ? articleBody.select("p[data-component-name=paragraph]")
                : doc.select("p[data-component-name=paragraph]");
        if (paragraphs.isEmpty()) {
            return null;
        }
        Element headline = doc.selectFirst("h1");
        String body = paragraphs.text();
        if (headline == null) {
            return body;
        }
        return headline.text() + "\n\n" + body;
    }

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return ARTICLE_URL_PATTERN.matcher(url).find();
    }

    @Override
    public String siteName() {
        return "cnn";
    }
}
