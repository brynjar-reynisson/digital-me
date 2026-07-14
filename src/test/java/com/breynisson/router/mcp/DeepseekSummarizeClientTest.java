package com.breynisson.router.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepseekSummarizeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsSummaryFromSingleTextLine() {
        String ndjson = """
                {"type":"step_start","part":{"type":"step-start"}}
                {"type":"text","part":{"type":"text","text":"A short summary."}}
                {"type":"step_finish","part":{"type":"step-finish"}}
                """;
        assertEquals("A short summary.", DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }

    @Test
    void lastTextLineWinsWhenMultiplePresent() {
        String ndjson = """
                {"type":"text","part":{"type":"text","text":"draft one"}}
                {"type":"text","part":{"type":"text","text":"final summary"}}
                """;
        assertEquals("final summary", DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }

    @Test
    void malformedLineIsIgnored() {
        String ndjson = """
                not json at all
                {"type":"text","part":{"type":"text","text":"still works"}}
                """;
        assertEquals("still works", DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }

    @Test
    void returnsNullWhenNoTextLinePresent() {
        String ndjson = """
                {"type":"step_start","part":{"type":"step-start"}}
                {"type":"step_finish","part":{"type":"step-finish"}}
                """;
        assertNull(DeepseekSummarizeClient.extractSummary(ndjson, objectMapper));
    }
}
