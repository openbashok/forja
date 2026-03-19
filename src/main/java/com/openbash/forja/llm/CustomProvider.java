package com.openbash.forja.llm;

import java.util.List;

/**
 * OpenAI-compatible custom endpoint provider.
 */
public class CustomProvider extends OpenAIProvider {

    private final String customModel;

    public CustomProvider(String apiKey, String endpoint, String model) {
        super(apiKey, endpoint);
        this.customModel = model;
    }

    @Override
    public List<String> availableModels() {
        return List.of(customModel);
    }

    @Override
    public String providerName() {
        return "Custom";
    }
}
