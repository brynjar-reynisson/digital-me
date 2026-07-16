package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedditPageHandlerTest {

    private final RedditPageHandler handler = new RedditPageHandler();

    @Test
    void matchesRedditUrl() {
        assertThat(handler.matches("https://www.reddit.com/r/OpenAI/comments/1uxfs3r/system_architects_are_about_to_become_one_of_the/")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void extractsHeadlinePostBodyAndCommentsFromPostPage() {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <h1 slot="title">Should I confront my roommate about this?</h1>
                <shreddit-post-text-body slot="text-body" view-context="CommentsPage">
                  <div property="schema:articleBody">
                    <p>My roommate has been leaving dishes in the sink for two weeks straight and I am at my wit's end.</p>
                  </div>
                </shreddit-post-text-body>
                <shreddit-comment thingid="t1_abc123" depth="0">
                  <div slot="comment">Just talk to them directly, passive aggressive notes never work.</div>
                </shreddit-comment>
                <shreddit-comment thingid="t1_def456" depth="1">
                  <div slot="comment">Agreed, communication is key in any living situation.</div>
                </shreddit-comment>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Should I confront my roommate about this?");
        assertThat(result).contains("leaving dishes in the sink for two weeks straight");
        assertThat(result).contains("Just talk to them directly, passive aggressive notes never work.");
        assertThat(result).contains("Agreed, communication is key in any living situation.");
    }

    @Test
    void extractsPostWithNoComments() {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <h1 slot="title">A quiet post with no replies yet</h1>
                <shreddit-post-text-body slot="text-body" view-context="CommentsPage">
                  <div property="schema:articleBody">
                    <p>Just sharing a thought with nobody to respond yet.</p>
                  </div>
                </shreddit-post-text-body>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("A quiet post with no replies yet");
        assertThat(result).contains("Just sharing a thought with nobody to respond yet.");
    }

    @Test
    void returnsNullForFeedShapedPage() {
        String html = """
                <html>
                <body>
                <nav>Home Popular All Explore</nav>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a post about relocating to a new city for work.</div>
                </shreddit-post>
                <shreddit-post view-context="SubredditFeed">
                  <div property="schema:articleBody">Teaser text for a different post about a weekend trip.</div>
                </shreddit-post>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }

    @Test
    void looksLikeArticleUrlForPostPath() {
        assertThat(handler.looksLikeArticleUrl("https://www.reddit.com/r/OpenAI/comments/1uxfs3r/system_architects_are_about_to_become_one_of_the/")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.reddit.com/")).isFalse();
    }

    @Test
    void siteNameIsReddit() {
        assertThat(handler.siteName()).isEqualTo("reddit");
    }
}
