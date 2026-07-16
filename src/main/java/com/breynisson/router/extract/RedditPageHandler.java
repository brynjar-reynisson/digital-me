package com.breynisson.router.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class RedditPageHandler implements PageHandler {

    @Override
    public boolean matches(String url) {
        return url.contains("reddit.com");
    }

    @Override
    public String extract(Document doc) {
        Element postBody = doc.selectFirst("shreddit-post-text-body[view-context=CommentsPage] div[property=\"schema:articleBody\"]");
        if (postBody == null) {
            return null;
        }
        Element headline = doc.selectFirst("h1[slot=title]");
        String comments = doc.select("shreddit-comment div[slot=comment]").text();
        String body = comments.isEmpty() ? postBody.text() : postBody.text() + "\n\n" + comments;
        if (headline == null) {
            return body;
        }
        return headline.text() + "\n\n" + body;
    }

    @Override
    public boolean looksLikeArticleUrl(String url) {
        return url.contains("/comments/");
    }

    @Override
    public String siteName() {
        return "reddit";
    }
}
