package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FotboltiPageHandlerTest {

    private final FotboltiPageHandler handler = new FotboltiPageHandler();

    @Test
    void matchesFotboltiUrl() {
        assertThat(handler.matches("https://fotbolti.net/news/16-07-2026/otrulegir-yfirburdur-argentinu")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void extractsHeadlineAndBothBodyPartsFromArticlePage() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="p-(--space-4)">
                  <h1 class="font-heading text-2xl font-bold uppercase leading-tight text-text-primary lg:text-3xl">Argentina Dominates After England Takes Lead</h1>
                  <div class="space-y-(--space-4)">
                    <div class="font-body text-base leading-8 text-text-primary">The manager faced heavy criticism after the team lost in the semi-final.</div>
                    <div class="lg:hidden"></div>
                    <a href="/news/other-article" class="flex overflow-hidden">
                      <h3 class="mt-(--space-1) text-base leading-normal">Related: Manager Defends His Decisions</h3>
                    </a>
                    <div class="article-html font-body text-base leading-8 text-text-primary">Statistics show a complete turnaround after the opening goal was scored.</div>
                  </div>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        String result = handler.extract(doc);

        assertThat(result).contains("Argentina Dominates After England Takes Lead");
        assertThat(result).contains("The manager faced heavy criticism after the team lost in the semi-final.");
        assertThat(result).contains("Statistics show a complete turnaround after the opening goal was scored.");
        assertThat(result).doesNotContain("Related: Manager Defends His Decisions");
    }

    @Test
    void returnsNullWhenNoMatchingBodyDivsPresent() {
        String html = """
                <html>
                <body>
                <nav>Home News Sports Life Opinion</nav>
                <div class="space-y-(--space-2)">
                  <a href="/news/1"><span class="line-clamp-2">Argentina Dominates After England Takes Lead</span></a>
                  <a href="/news/2"><span class="line-clamp-2">Another Story Headline</span></a>
                </div>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html);

        assertThat(handler.extract(doc)).isNull();
    }

    @Test
    void looksLikeArticleUrlForArticlePath() {
        assertThat(handler.looksLikeArticleUrl("https://fotbolti.net/news/16-07-2026/otrulegir-yfirburdur-argentinu")).isTrue();
    }

    @Test
    void doesNotLookLikeArticleUrlForFrontPage() {
        assertThat(handler.looksLikeArticleUrl("https://www.fotbolti.net")).isFalse();
    }

    @Test
    void siteNameIsFotbolti() {
        assertThat(handler.siteName()).isEqualTo("fotbolti");
    }
}
