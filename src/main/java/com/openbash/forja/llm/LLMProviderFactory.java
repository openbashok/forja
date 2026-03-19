package com.openbash.forja.llm;

import com.openbash.forja.config.ConfigManager;

public class LLMProviderFactory {

    private final ConfigManager config;

    public LLMProviderFactory(ConfigManager config) {
        this.config = config;
    }

    public LLMProvider create() {
        String provider = config.getProvider();
        String apiKey = config.getApiKey();

        return switch (provider) {
            case "Anthropic" -> new AnthropicProvider(apiKey);
            case "OpenAI" -> new OpenAIProvider(apiKey);
            case "Custom" -> new CustomProvider(apiKey, config.getCustomEndpoint(), config.getModel());
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }
}
