package com.openbash.forja.util;

import java.util.Map;

public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    // Cost per 1M tokens (input, output) in USD
    private static final Map<String, double[]> MODEL_COSTS = Map.of(
            "claude-sonnet-4-20250514", new double[]{3.0, 15.0},
            "claude-haiku-4-20250414", new double[]{0.80, 4.0},
            "claude-opus-4-20250514", new double[]{15.0, 75.0},
            "gpt-4o", new double[]{2.50, 10.0},
            "gpt-4o-mini", new double[]{0.15, 0.60},
            "gpt-4.1", new double[]{2.0, 8.0},
            "gpt-4.1-mini", new double[]{0.40, 1.60}
    );

    private TokenEstimator() {}

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    public static double estimateCostUsd(int inputTokens, int outputTokens, String model) {
        double[] costs = MODEL_COSTS.getOrDefault(model, new double[]{3.0, 15.0});
        return (inputTokens * costs[0] + outputTokens * costs[1]) / 1_000_000.0;
    }

    public static String formatCost(double usd) {
        if (usd < 0.01) {
            return String.format("$%.4f", usd);
        }
        return String.format("$%.2f", usd);
    }
}
