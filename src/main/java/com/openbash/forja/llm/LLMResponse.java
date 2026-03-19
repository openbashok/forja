package com.openbash.forja.llm;

public class LLMResponse {

    private final String content;
    private final int inputTokens;
    private final int outputTokens;
    private final String model;

    public LLMResponse(String content, int inputTokens, int outputTokens, String model) {
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.model = model;
    }

    public String getContent() { return content; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public String getModel() { return model; }

    public double estimatedCostUsd() {
        return com.openbash.forja.util.TokenEstimator.estimateCostUsd(inputTokens, outputTokens, model);
    }
}
