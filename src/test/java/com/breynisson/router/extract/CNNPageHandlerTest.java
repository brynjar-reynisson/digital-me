package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CNNPageHandlerTest {

    private final CNNPageHandler handler = new CNNPageHandler();

    @Test
    void matchesCNNUrl() {
        assertThat(handler.matches("https://edition.cnn.com/2026/07/15/science/new-jersey-fireball-rare-meteorite")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void extractsHeadlineAndBodyFromArticlePage() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline__text" id="maincontent">Meteorite Sheds Light on Ancient Water</h1>
                <div class="article__content" itemprop="articleBody">
                  <p data-component-name="paragraph">A meteorite that crashed through the roof of a home could shed light on ancient water in the solar system.</p>
                  <div data-component-name="related-content" class="related-content">
                    <p class="related-content__headline">Related article: Scientists found something else in an asteroid</p>
                  </div>
                  <p data-component-name="paragraph">Only one fragment was recovered from the meteorite.</p>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Meteorite Sheds Light on Ancient Water");
        assertThat(result).contains("shed light on ancient water in the solar system");
        assertThat(result).contains("Only one fragment was recovered from the meteorite.");
        assertThat(result).doesNotContain("Scientists found something else");
    }

    @Test
    void extractsHeadlineAndBodyFromLiveBlogPage() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline_live-story__text" id="maincontent">Iran War Live Updates</h1>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Iran signals openness to diplomacy</h2>
                  <p data-component-name="paragraph">Officials say Iran remains open to diplomatic talks despite recent tensions.</p>
                </article>
                <article data-component-name="live-story-post">
                  <h2 class="live-story-post__headline">Trump responds to latest developments</h2>
                  <p data-component-name="paragraph">The president addressed reporters about the ongoing situation in the region.</p>
                </article>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Iran War Live Updates");
        assertThat(result).contains("Officials say Iran remains open to diplomatic talks despite recent tensions.");
        assertThat(result).contains("The president addressed reporters about the ongoing situation in the region.");
        assertThat(result).doesNotContain("Iran signals openness to diplomacy");
        assertThat(result).doesNotContain("Trump responds to latest developments");
    }

    @Test
    void returnsHeadlineOnlyWhenArticleBodyPresentButHasNoMatchingParagraphs() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline__text" id="maincontent">Meteorite Sheds Light on Ancient Water</h1>
                <div class="article__content" itemprop="articleBody">
                  <div data-component-name="video">Video content only, no paragraph markup.</div>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).isNotNull();
        assertThat(result).contains("Meteorite Sheds Light on Ancient Water");
    }

    @Test
    void scopesToArticleBodyWhenPresentEvenIfOtherParagraphsExistElsewhere() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <h1 data-editable="headlineText" class="headline__text" id="maincontent">Meteorite Sheds Light on Ancient Water</h1>
                <div class="teaser">
                  <p data-component-name="paragraph">This is unrelated teaser content from elsewhere on the page.</p>
                </div>
                <div class="article__content" itemprop="articleBody">
                  <p data-component-name="paragraph">Only one fragment was recovered from the meteorite.</p>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Only one fragment was recovered from the meteorite.");
        assertThat(result).doesNotContain("unrelated teaser content");
    }

    @Test
    void returnsNullWhenNoArticleBodyPresent() {
        String html = """
                <html>
                <body>
                <nav>Home US World Politics Business</nav>
                <div class="zone">
                  <a href="/2026/07/15/science/some-article"><span class="headline">Meteorite Sheds Light on Ancient Water</span></a>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }

    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com/2026/07/15/science/new-jersey-fireball-rare-meteorite")).isTrue();
    }

    @Test
    void looksLikeArticleUrlForLiveBlog() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com/2026/07/16/world/live-news/iran-war-trump")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://edition.cnn.com")).isFalse();
    }

    @Test
    void siteNameIsCnn() {
        assertThat(handler.siteName()).isEqualTo("cnn");
    }
}
