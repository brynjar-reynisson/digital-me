package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DvPageHandlerTest {

    private final DvPageHandler handler = new DvPageHandler();

    @Test
    void matchesDvUrl() {
        assertThat(handler.matches("https://www.dv.is/433/2026/07/15/storstjarna-faer-a-baukinn")).isTrue();
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
                <nav>Home News Sports Life Opinion</nav>
                <article class="node node--article">
                  <div class="node__content">
                    <div class="article-header">
                      <div class="field field--name-title">
                        <h1>Star Gets Called Out</h1>
                      </div>
                    </div>
                    <div class="article-body photoswipe-gallery">
                      <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                        <p>The player was criticized after photos surfaced from a party.</p>
                        <article class="media media--type-image media--view-mode-default">
                          <div class="field__label visually-hidden">Mynd</div>
                        </article>
                        <p>Fans reacted strongly to the news on social media.</p>
                      </div>
                    </div>
                    <div class="article-footer-region">
                      <h2>Fleiri fréttir</h2>
                      <div class="views-content">Unrelated Story About Something Else</div>
                    </div>
                  </div>
                </article>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Star Gets Called Out");
        assertThat(result).contains("The player was criticized after photos surfaced from a party.");
        assertThat(result).contains("Fans reacted strongly to the news on social media.");
        assertThat(result).doesNotContain("Mynd");
        assertThat(result).doesNotContain("Unrelated Story");
    }

    @Test
    void returnsNullWhenNoArticleBodyPresent() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <div class="tarot-promo"><h4>Tarot Cards</h4></div>
                </div>
                <div class="clearfix text-formatted field field--name-body field--type-text-with-summary field--label-hidden field__item">
                  <p>Address 123<br>City</p>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }
}
