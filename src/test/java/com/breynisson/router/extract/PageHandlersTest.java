package com.breynisson.router.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageHandlersTest {

    @Test
    void findsVisirHandlerForVisirUrl() {
        assertThat(PageHandlers.find("https://www.visir.is/g/123/some-article"))
                .containsInstanceOf(VisirPageHandler.class);
    }

    @Test
    void returnsEmptyForUnrelatedUrl() {
        assertThat(PageHandlers.find("https://www.example.com/page")).isEmpty();
    }

    @Test
    void findsDVHandlerForDVUrl() {
        assertThat(PageHandlers.find("https://www.dv.is/433/2026/07/15/some-article"))
                .containsInstanceOf(DVPageHandler.class);
    }

    @Test
    void findsFotboltiHandlerForFotboltiUrl() {
        assertThat(PageHandlers.find("https://fotbolti.net/news/16-07-2026/some-article"))
                .containsInstanceOf(FotboltiPageHandler.class);
    }

    @Test
    void findsCNNHandlerForCNNUrl() {
        assertThat(PageHandlers.find("https://edition.cnn.com/2026/07/15/science/some-article"))
                .containsInstanceOf(CNNPageHandler.class);
    }
}
