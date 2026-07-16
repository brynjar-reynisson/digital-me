package com.breynisson.router;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeSessionIndexerTest {

    @Test
    void buildFileNameUsesDayHourMinuteSecondPrefixLikeOtherMcpResources() {
        LocalDateTime sessionStart = LocalDateTime.of(2026, 7, 16, 16, 15, 13);

        String fileName = ClaudeSessionIndexer.buildFileName("digital-me", sessionStart);

        assertEquals("16-16-15-13-claudecode-digital-me.txt", fileName);
    }
}
