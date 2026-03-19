package com.openbash.forja.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {

    @Test
    void estimateTokens_normalText() {
        // 20 chars -> ~5 tokens
        assertEquals(5, TokenEstimator.estimateTokens("12345678901234567890"));
    }

    @Test
    void estimateTokens_emptyAndNull() {
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(0, TokenEstimator.estimateTokens(null));
    }

    @Test
    void estimateTokens_roundsUp() {
        // 5 chars -> ceil(5/4) = 2
        assertEquals(2, TokenEstimator.estimateTokens("hello"));
    }

    @Test
    void estimateCost_knownModel() {
        // claude-sonnet-4: $3/1M input, $15/1M output
        double cost = TokenEstimator.estimateCostUsd(1_000_000, 0, "claude-sonnet-4-20250514");
        assertEquals(3.0, cost, 0.01);

        double outputCost = TokenEstimator.estimateCostUsd(0, 1_000_000, "claude-sonnet-4-20250514");
        assertEquals(15.0, outputCost, 0.01);
    }

    @Test
    void estimateCost_unknownModelUsesDefault() {
        double cost = TokenEstimator.estimateCostUsd(1_000_000, 0, "unknown-model");
        // Default: $3/1M input
        assertEquals(3.0, cost, 0.01);
    }

    @Test
    void formatCost_smallAmount() {
        assertEquals("$0.0010", TokenEstimator.formatCost(0.001));
    }

    @Test
    void formatCost_largeAmount() {
        assertEquals("$1.50", TokenEstimator.formatCost(1.5));
    }
}
