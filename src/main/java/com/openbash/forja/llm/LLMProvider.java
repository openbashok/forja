package com.openbash.forja.llm;

import java.util.List;

public interface LLMProvider {

    LLMResponse chat(List<Message> messages, String model, int maxTokens) throws LLMException;

    String testConnection() throws LLMException;

    List<String> availableModels();

    String providerName();
}
