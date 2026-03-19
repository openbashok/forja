package com.openbash.forja.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.openbash.forja.util.HttpUtil;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

public class OpenAIProvider implements LLMProvider {

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 3;

    private final String apiKey;
    private final String apiUrl;

    public OpenAIProvider(String apiKey) {
        this(apiKey, DEFAULT_API_URL);
    }

    public OpenAIProvider(String apiKey, String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
    }

    @Override
    public LLMResponse chat(List<Message> messages, String model, int maxTokens) throws LLMException {
        JsonArray messagesArray = new JsonArray();
        for (Message msg : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role());
            m.addProperty("content", msg.content());
            messagesArray.add(m);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messagesArray);

        return executeWithRetry(body);
    }

    @Override
    public String testConnection() throws LLMException {
        LLMResponse response = chat(
                List.of(Message.system("You are a test assistant."), Message.user("Say 'ok' and nothing else.")),
                availableModels().get(0),
                16
        );
        return "Connected to " + providerName() + ". Model: " + response.getModel()
                + " (tokens: " + response.getInputTokens() + " in, " + response.getOutputTokens() + " out)";
    }

    @Override
    public List<String> availableModels() {
        return List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini");
    }

    @Override
    public String providerName() {
        return "OpenAI";
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
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = HttpUtil.getClient().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException("OpenAI API error: " + response.code() + " - " + responseBody, response.code());
            }

            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            String content = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            JsonObject usage = json.getAsJsonObject("usage");
            int inputTokens = usage.get("prompt_tokens").getAsInt();
            int outputTokens = usage.get("completion_tokens").getAsInt();
            String model = json.get("model").getAsString();

            return new LLMResponse(content, inputTokens, outputTokens, model);
        } catch (IOException e) {
            throw new LLMException("Network error: " + e.getMessage(), 0, e);
        }
    }
}
