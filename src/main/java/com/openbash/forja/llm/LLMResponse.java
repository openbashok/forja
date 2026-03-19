package com.openbash.forja.llm;

public class LLMResponse {

    private final String content;
    private final int inputTokens;
    private final int outputTokens;
    private final String model;
    private final String stopReason;

    public LLMResponse(String content, int inputTokens, int outputTokens, String model) {
        this(content, inputTokens, outputTokens, model, "end_turn");
    }

    public LLMResponse(String content, int inputTokens, int outputTokens, String model, String stopReason) {
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.model = model;
        this.stopReason = stopReason != null ? stopReason : "end_turn";
    }

    public String getContent() { return content; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public String getModel() { return model; }
    public String getStopReason() { return stopReason; }

    /** True if the response was cut short because it hit the max_tokens limit. */
    public boolean isTruncated() {
        return "max_tokens".equals(stopReason) || "length".equals(stopReason);
    }

    public double estimatedCostUsd() {
        return com.openbash.forja.util.TokenEstimator.estimateCostUsd(inputTokens, outputTokens, model);
    }
}
