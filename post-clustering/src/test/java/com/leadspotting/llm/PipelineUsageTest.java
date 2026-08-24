package com.leadspotting.llm;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineUsageTest {
    @Test
    void aggregatesStagesAndUsesConfiguredModelPrices() {
        PipelineUsage usage = new PipelineUsage();
        usage.add("embedding", OpenAi.EMBED_MODEL, 1_000_000, 0, 1_000_000);
        usage.add("summarization", OpenAi.CHAT_MODEL, 1_000_000, 100_000, 1_100_000);
        usage.recordDuration("summarization", 500);

        PipelineUsage.Snapshot result = usage.snapshot(1234);

        assertEquals(1234, result.durationMs());
        assertEquals(2_000_000, result.inputTokens());
        assertEquals(100_000, result.outputTokens());
        assertEquals(2_100_000, result.totalTokens());
        assertEquals(new BigDecimal("0.230000"), result.estimatedCostUsd());
        assertTrue(result.usageDetailsJson().contains("summarization"));
        assertTrue(result.usageDetailsJson().contains("stageDurationsMs"));
    }
}
