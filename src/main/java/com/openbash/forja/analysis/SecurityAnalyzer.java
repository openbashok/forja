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
        this.contextBuilder = new ContextBuilder(Integer.MAX_VALUE);
    }

    public List<Finding> analyze(AppModel appModel) throws LLMException {
        String systemPrompt = promptManager.getWithRules("analysis_system");
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
        String json = extractJson(content);
        if (json == null) {
            findings.add(new Finding(
                    "Analysis Result (unparsed)",
                    Severity.INFO,
                    content,
                    "", List.of(), "Review raw analysis output.", List.of()
            ));
            return findings;
        }

        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray findingsArray = root.getAsJsonArray("findings");
            if (findingsArray == null) {
                // Maybe the root IS the array
                JsonArray directArray = GSON.fromJson(json, JsonArray.class);
                if (directArray != null) findingsArray = directArray;
            }
            if (findingsArray == null) return findings;

            for (JsonElement elem : findingsArray) {
                try {
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
                } catch (Exception ignored) {
                    // Skip malformed individual findings, don't fail the whole batch
                }
            }
        } catch (Exception e) {
            findings.add(new Finding(
                    "Analysis Result (unparsed)",
                    Severity.INFO,
                    content,
                    "", List.of(), "Parse error: " + e.getMessage(), List.of()
            ));
        }
        return findings;
    }

    /**
     * Extract JSON from LLM response. Tries multiple strategies:
     * 1. Markdown fenced block (```json ... ``` or ``` ... ```)
     * 2. First { to last } (raw JSON object)
     * 3. First [ to last ] (raw JSON array)
     */
    static String extractJson(String content) {
        if (content == null || content.isBlank()) return null;
        String trimmed = content.trim();

        // Strategy 1: ```json ... ``` fenced block
        int jsonFenceStart = trimmed.indexOf("```json");
        if (jsonFenceStart >= 0) {
            int bodyStart = trimmed.indexOf('\n', jsonFenceStart);
            if (bodyStart < 0) bodyStart = jsonFenceStart + 7;
            else bodyStart++;
            int fenceEnd = trimmed.indexOf("```", bodyStart);
            if (fenceEnd > bodyStart) {
                String candidate = trimmed.substring(bodyStart, fenceEnd).trim();
                if (!candidate.isEmpty()) return candidate;
            }
        }

        // Strategy 2: ``` ... ``` generic fenced block
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int bodyStart = trimmed.indexOf('\n', fenceStart);
            if (bodyStart >= 0) {
                bodyStart++;
                int fenceEnd = trimmed.indexOf("```", bodyStart);
                if (fenceEnd > bodyStart) {
                    String candidate = trimmed.substring(bodyStart, fenceEnd).trim();
                    if (candidate.startsWith("{") || candidate.startsWith("[")) return candidate;
                }
            }
        }

        // Strategy 3: Find outermost { ... } (JSON object)
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        // Strategy 4: Find outermost [ ... ] (JSON array)
        int firstBracket = trimmed.indexOf('[');
        int lastBracket = trimmed.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            return trimmed.substring(firstBracket, lastBracket + 1);
        }

        return null;
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
