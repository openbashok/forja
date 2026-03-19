package com.openbash.forja.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIProviderTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void providerName() {
        OpenAIProvider provider = new OpenAIProvider("test-key");
        assertEquals("OpenAI", provider.providerName());
    }

    @Test
    void availableModels() {
        OpenAIProvider provider = new OpenAIProvider("test-key");
        List<String> models = provider.availableModels();
        assertTrue(models.contains("gpt-4o"));
        assertTrue(models.contains("gpt-4o-mini"));
    }

    @Test
    void chatParsesResponse() throws Exception {
        String responseJson = """
                {
                    "id": "chatcmpl-test",
                    "model": "gpt-4o",
                    "choices": [{"message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
                }
                """;
        server.enqueue(new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

        OpenAIProvider provider = new OpenAIProvider("test-key", server.url("/v1/chat/completions").toString());
        LLMResponse response = provider.chat(
                List.of(Message.user("Say ok")), "gpt-4o", 16
        );

        assertEquals("ok", response.getContent());
        assertEquals(10, response.getInputTokens());
        assertEquals(1, response.getOutputTokens());
        assertEquals("gpt-4o", response.getModel());
    }

    @Test
    void chatSendsCorrectHeaders() throws Exception {
        String responseJson = """
                {
                    "model": "gpt-4o",
                    "choices": [{"message": {"role": "assistant", "content": "ok"}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 1}
                }
                """;
        server.enqueue(new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

        OpenAIProvider provider = new OpenAIProvider("sk-test-key", server.url("/v1/chat/completions").toString());
        provider.chat(List.of(Message.user("test")), "gpt-4o", 16);

        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"));
        assertTrue(request.getHeader("Content-Type").contains("application/json"));
    }

    @Test
    void chatThrowsOnHttpError() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\": \"invalid api key\"}"));

        OpenAIProvider provider = new OpenAIProvider("bad-key", server.url("/v1/chat/completions").toString());

        LLMException ex = assertThrows(LLMException.class, () ->
                provider.chat(List.of(Message.user("test")), "gpt-4o", 16));
        assertEquals(401, ex.getStatusCode());
        assertTrue(ex.isAuthError());
    }

    @Test
    void chatThrowsRateLimitException() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\": \"rate limited\"}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\": \"rate limited\"}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\": \"rate limited\"}"));

        OpenAIProvider provider = new OpenAIProvider("key", server.url("/v1/chat/completions").toString());

        LLMException ex = assertThrows(LLMException.class, () ->
                provider.chat(List.of(Message.user("test")), "gpt-4o", 16));
        assertTrue(ex.isRateLimit());
    }

    @Test
    void chatSendsSystemMessageCorrectly() throws Exception {
        String responseJson = """
                {
                    "model": "gpt-4o",
                    "choices": [{"message": {"role": "assistant", "content": "ok"}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 1}
                }
                """;
        server.enqueue(new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

        OpenAIProvider provider = new OpenAIProvider("key", server.url("/v1/chat/completions").toString());
        provider.chat(
                List.of(Message.system("You are helpful"), Message.user("test")),
                "gpt-4o", 16
        );

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("You are helpful"));
    }
}
