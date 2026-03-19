package com.openbash.forja.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.openbash.forja.util.HttpUtil;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

public class AnthropicProvider implements LLMProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 3;

    private final String apiKey;

    public AnthropicProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public LLMResponse chat(List<Message> messages, String model, int maxTokens) throws LLMException {
        String systemPrompt = null;
        JsonArray messagesArray = new JsonArray();

        for (Message msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
            } else {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.role());
                m.addProperty("content", msg.content());
                messagesArray.add(m);
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messagesArray);
        if (systemPrompt != null) {
            body.addProperty("system", systemPrompt);
        }

        return executeWithRetry(body);
    }

    @Override
    public String testConnection() throws LLMException {
        LLMResponse response = chat(
                List.of(Message.user("Say 'ok' and nothing else.")),
                availableModels().get(0),
                16
        );
        return "Connected to Anthropic. Model: " + response.getModel()
                + " (tokens: " + response.getInputTokens() + " in, " + response.getOutputTokens() + " out)";
    }

    @Override
    public List<String> availableModels() {
        return List.of(
                "claude-sonnet-4-20250514",
                "claude-haiku-4-20250414",
                "claude-opus-4-20250514"
        );
    }

    @Override
    public String providerName() {
        return "Anthropic";
    }

    private LLMResponse executeWithRetry(JsonObject body) throws LLMException {
        LLMException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return execute(body);
            } catch (LLMException e) {
                lastException = e;
                if (!e.isRateLimit() || attempt == MAX_RETRIES - 1) {
                    throw e;
                }
                try {
                    Thread.sleep((long) Math.pow(2, attempt) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LLMException("Interrupted during retry", 0, ie);
                }
            }
        }
        throw lastException;
    }

    private LLMResponse execute(JsonObject body) throws LLMException {
        Request request = new Request.Builder()
                .url(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = HttpUtil.getClient().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException("Anthropic API error: " + response.code() + " - " + responseBody, response.code());
            }

            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            String content = json.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            JsonObject usage = json.getAsJsonObject("usage");
            int inputTokens = usage.get("input_tokens").getAsInt();
            int outputTokens = usage.get("output_tokens").getAsInt();
            String model = json.get("model").getAsString();

            return new LLMResponse(content, inputTokens, outputTokens, model);
        } catch (IOException e) {
            throw new LLMException("Network error: " + e.getMessage(), 0, e);
        }
    }
}
