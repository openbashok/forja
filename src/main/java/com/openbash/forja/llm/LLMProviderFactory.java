package com.openbash.forja.llm;

import com.openbash.forja.config.ConfigManager;

import java.util.List;

public class LLMProviderFactory {

    private final ConfigManager config;

    public LLMProviderFactory(ConfigManager config) {
        this.config = config;
    }

    public LLMProvider create() {
        String provider = config.getProvider();
        String apiKey = config.getApiKey();

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key not configured. Go to Forja > Config tab and enter your API key.");
        }

        LLMProvider base = switch (provider) {
            case "Anthropic" -> new AnthropicProvider(apiKey);
            case "OpenAI" -> new OpenAIProvider(apiKey);
            case "Custom" -> new CustomProvider(apiKey, config.getCustomEndpoint(), config.getModel());
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };

        // Wrap to track costs automatically
        return new TrackedProvider(base);
    }

    /**
     * Wrapper that records every LLM call in the global CostTracker.
     */
    private static class TrackedProvider implements LLMProvider {
        private final LLMProvider delegate;

        TrackedProvider(LLMProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public LLMResponse chat(List<Message> messages, String model, int maxTokens) throws LLMException {
            LLMResponse response = delegate.chat(messages, model, maxTokens);
            CostTracker.getInstance().record(response);
            return response;
        }

        @Override
        public String testConnection() throws LLMException {
            return delegate.testConnection();
        }

        @Override
        public List<String> availableModels() {
            return delegate.availableModels();
        }

        @Override
        public String providerName() {
            return delegate.providerName();
        }
    }
}
