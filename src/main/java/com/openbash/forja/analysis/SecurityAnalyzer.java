package com.openbash.forja.analysis;

import com.google.gson.*;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.util.ArrayList;
import java.util.List;

public class SecurityAnalyzer {

    private final LLMProvider provider;
    private final ConfigManager config;
    private final PromptManager promptManager;
    private final ContextBuilder contextBuilder;
    private static final Gson GSON = new Gson();

    public SecurityAnalyzer(LLMProvider provider, ConfigManager config, PromptManager promptManager) {
        this.provider = provider;
        this.config = config;
        this.promptManager = promptManager;
        this.contextBuilder = new ContextBuilder(8000);
    }

    public List<Finding> analyze(AppModel appModel) throws LLMException {
        String systemPrompt = promptManager.get("analysis_system");
        String context = contextBuilder.buildContext(appModel);

        String userPrompt = "Analyze the following web application traffic for security vulnerabilities.\n\n" + context;

        LLMResponse response = provider.chat(
                List.of(Message.system(systemPrompt), Message.user(userPrompt)),
                config.getModel(),
                4096
        );

        return parseFindings(response.getContent());
    }

    public int estimateInputTokens(AppModel appModel) {
        return contextBuilder.estimateContextTokens(appModel) + 500; // system prompt overhead
    }

    List<Finding> parseFindings(String content) {
        List<Finding> findings = new ArrayList<>();
        try {
            // Extract JSON from potential markdown code blocks
            String json = content.trim();
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }

            JsonObject root = GSON.fromJson(json.trim(), JsonObject.class);
            JsonArray findingsArray = root.getAsJsonArray("findings");
            if (findingsArray == null) return findings;

            for (JsonElement elem : findingsArray) {
                JsonObject f = elem.getAsJsonObject();
                findings.add(new Finding(
                        getStr(f, "title"),
                        Severity.fromString(getStr(f, "severity")),
                        getStr(f, "description"),
                        getStr(f, "evidence"),
                        getStrList(f, "affected_endpoints"),
                        getStr(f, "recommendation"),
                        getStrList(f, "cwes")
                ));
            }
        } catch (Exception e) {
            // If parsing fails, create a single finding with raw content
            findings.add(new Finding(
                    "Analysis Result (unparsed)",
                    Severity.INFO,
                    content,
                    "", List.of(), "Review raw analysis output.", List.of()
            ));
        }
        return findings;
    }

    private static String getStr(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : "";
    }

    private static List<String> getStrList(JsonObject obj, String key) {
        List<String> list = new ArrayList<>();
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                list.add(item.getAsString());
            }
        }
        return list;
    }
}
