package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisirPageHandlerTest {

    private final VisirPageHandler handler = new VisirPageHandler();

    @Test
    void matchesVisirUrl() {
        assertThat(handler.matches("https://www.visir.is/g/20262909759d/messi-allt-i-ollu-thegar-argentina-for-i-ur-slita-leikinn")).isTrue();
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
                <nav>Home News Sports Weather Opinion Most Read: Some Other Story</nav>
                <article class="article-single -sport">
                    <header class="article-single__header">
                        <h1>Team Wins Championship Final</h1>
                    </header>
                    <div class="article-single__content">
                        <article>
                            <div itemprop="articleBody">
                                <p>The home team secured a dramatic victory in the final minutes.</p>
                                <p>Fans celebrated wildly across the city.</p>
                            </div>
                        </article>
                    </div>
                </article>
                <div class="article-item">Most Read: Unrelated Story About Something Else</div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Team Wins Championship Final");
        assertThat(result).contains("dramatic victory in the final minutes");
        assertThat(result).contains("Fans celebrated wildly");
        assertThat(result).doesNotContain("Unrelated Story");
        assertThat(result).doesNotContain("Most Read");
    }

    @Test
    void returnsNullWhenNoArticleBodyPresent() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Weather Opinion</nav>
                <div class="article-item"><a href="/g/1">Team Wins Championship Final</a></div>
                <div class="article-item"><a href="/g/2">Another Unrelated Story</a></div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }

    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://www.visir.is/g/20262909759d/messi-allt-i-ollu")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.visir.is")).isFalse();
    }

    @Test
    void siteNameIsVisir() {
        assertThat(handler.siteName()).isEqualTo("visir");
    }
}
