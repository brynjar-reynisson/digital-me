package com.breynisson.router.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallbackSummarizeClientTest {

    @Test
    void primarySuccessIsReturnedWithoutCallingFallback() {
        SummarizeClient primary = text -> "primary summary";
        SummarizeClient fallback = text -> { throw new AssertionError("fallback should not be called"); };

        FallbackSummarizeClient client = new FallbackSummarizeClient(primary, fallback);

        assertEquals("primary summary", client.summarize("some text"));
    }

    @Test
    void primaryNullFallsBackToSecondary() {
        SummarizeClient primary = text -> null;
        SummarizeClient fallback = text -> "fallback summary";

        FallbackSummarizeClient client = new FallbackSummarizeClient(primary, fallback);

        assertEquals("fallback summary", client.summarize("some text"));
    }

    @Test
    void bothNullReturnsNull() {
        SummarizeClient primary = text -> null;
        SummarizeClient fallback = text -> null;

        FallbackSummarizeClient client = new FallbackSummarizeClient(primary, fallback);

        assertNull(client.summarize("some text"));
    }

    @Test
    void isAvailableTrueWhenOnlyPrimaryAvailable() {
        SummarizeClient primary = availableClient(true);
        SummarizeClient fallback = availableClient(false);

        assertTrue(new FallbackSummarizeClient(primary, fallback).isAvailable());
    }

    @Test
    void isAvailableTrueWhenOnlyFallbackAvailable() {
        SummarizeClient primary = availableClient(false);
        SummarizeClient fallback = availableClient(true);

        assertTrue(new FallbackSummarizeClient(primary, fallback).isAvailable());
    }

    @Test
    void isAvailableFalseWhenNeitherAvailable() {
        SummarizeClient primary = availableClient(false);
        SummarizeClient fallback = availableClient(false);

        assertFalse(new FallbackSummarizeClient(primary, fallback).isAvailable());
    }

    private static SummarizeClient availableClient(boolean available) {
        return new SummarizeClient() {
            @Override
            public String summarize(String text) {
                return null;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }
        };
    }
}
