package com.breynisson.router.extract;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WumoPageHandlerTest {

    private final WumoPageHandler handler = new WumoPageHandler();

    @Test
    void matchesWumoUrl() {
        assertThat(handler.matches("https://wumo.com/wumo/2013/03/05")).isTrue();
    }

    @Test
    void doesNotMatchUnrelatedUrl() {
        assertThat(handler.matches("https://www.example.com/some-article")).isFalse();
    }

    @Test
    void passesThroughIndexerPayload() {
        String payload = "Wumo cartoon 2013-03-05\n\nText in the cartoon: Pole vaulting never really caught on in Nepal\n\nScene: A mountain pass.";

        String result = handler.extract(Jsoup.parse(payload));

        assertThat(result).startsWith("Wumo cartoon 2013-03-05").contains("Pole vaulting").contains("A mountain pass.");
    }

    @Test
    void discardsBrowserPageCapture() {
        String html = "<html><body><nav>WUMO 5. March 2013 Share on Facebook COMMENTS LATEST WUMO</nav>"
                + "<p>pole vaulting never really caught on in Nepal</p><p>OUR WEEKLY NEWSLETTER Subscribe</p></body></html>";

        assertThat(handler.extract(Jsoup.parse(html))).isNull();
    }

    @Test
    void neverFallsBackToGenericExtraction() {
        assertThat(handler.looksLikeArticleUrl("https://wumo.com/wumo/2013/03/05")).isFalse();
    }
}
