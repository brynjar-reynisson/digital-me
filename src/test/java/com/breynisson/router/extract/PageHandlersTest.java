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
}
