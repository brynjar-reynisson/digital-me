package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class FotboltiPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("fotbolti.net");
    }

    @Override
    public String extract(Document doc) {
        Elements paragraphs = doc.select("div.font-body.text-base.leading-8");
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
}
