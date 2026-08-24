package com.leadspotnic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Accumulates API usage for one preprocessing run. Prices are USD per one million tokens. */
public final class PipelineUsage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final Map<String, Price> PRICES = Map.of(
            OpenAi.CHAT_MODEL, new Price("0.15", "0.60"),
            OpenAi.EMBED_MODEL, new Price("0.02", "0.00"));
    private final Map<String, MutableUsage> details = new LinkedHashMap<>();
    private final Map<String, Long> stageDurationsMs = new LinkedHashMap<>();

    public synchronized void add(String stage, String model, long input, long output, long total) {
        MutableUsage u = details.computeIfAbsent(stage + ":" + model,
                ignored -> new MutableUsage(stage, model));
        u.input += input; u.output += output; u.total += total;
    }

    public synchronized void recordDuration(String stage, long durationMs) {
        stageDurationsMs.merge(stage, durationMs, Long::sum);
    }

    public synchronized Snapshot snapshot(long durationMs) {
        long input = 0, output = 0, total = 0;
        BigDecimal cost = BigDecimal.ZERO;
        ObjectNode root = JSON.createObjectNode();
        ObjectNode durations = root.putObject("stageDurationsMs");
        stageDurationsMs.forEach(durations::put);
        ObjectNode stages = root.putObject("stages");
        for (MutableUsage u : details.values()) {
            input += u.input; output += u.output; total += u.total;
            BigDecimal itemCost = cost(u.model, u.input, u.output);
            cost = cost.add(itemCost);
            ObjectNode item = stages.putObject(u.stage + ":" + u.model);
            item.put("stage", u.stage); item.put("model", u.model);
            item.put("inputTokens", u.input); item.put("outputTokens", u.output);
            item.put("totalTokens", u.total); item.put("estimatedCostUsd", itemCost);
        }
        root.put("pricingBasis", "USD per 1M tokens at execution-time configuration");
        return new Snapshot(durationMs, input, output, total,
                cost.setScale(6, RoundingMode.HALF_UP), root.toString());
    }

    private static BigDecimal cost(String model, long input, long output) {
        Price p = PRICES.get(model);
        if (p == null) return BigDecimal.ZERO;
        return p.input.multiply(BigDecimal.valueOf(input)).add(p.output.multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
    }
    public record Snapshot(long durationMs, long inputTokens, long outputTokens, long totalTokens,
                           BigDecimal estimatedCostUsd, String usageDetailsJson) {}
    private record Price(BigDecimal input, BigDecimal output) {
        Price(String input, String output) { this(new BigDecimal(input), new BigDecimal(output)); }
    }
    private static final class MutableUsage {
        private final String stage, model; private long input, output, total;
        private MutableUsage(String stage, String model) { this.stage = stage; this.model = model; }
    }
}
