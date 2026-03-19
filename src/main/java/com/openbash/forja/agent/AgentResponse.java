package com.openbash.forja.agent;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;

public class AgentResponse {

    private final String response;
    private final List<AgentAction> actions;
    private final String status;    // "working", "done", "blocked" — used in Agent mode
    private final String nextStep;  // description of next step — used in Agent mode

    public AgentResponse(String response, List<AgentAction> actions) {
        this(response, actions, "done", null);
    }

    public AgentResponse(String response, List<AgentAction> actions, String status, String nextStep) {
        this.response = response;
        this.actions = actions;
        this.status = status != null ? status : "done";
        this.nextStep = nextStep;
    }

    public String getResponse() { return response; }
    public List<AgentAction> getActions() { return actions; }
    public String getStatus() { return status; }
    public String getNextStep() { return nextStep; }
    public boolean isWorking() { return "working".equals(status); }

    public static AgentResponse parse(String content) {
        try {
            String json = extractJson(content);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            String response = "";
            if (root.has("response") && !root.get("response").isJsonNull()) {
                response = root.get("response").getAsString();
            }

            List<AgentAction> actions = new ArrayList<>();
            if (root.has("actions") && root.get("actions").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("actions")) {
                    JsonObject actionObj = elem.getAsJsonObject();
                    String tool = actionObj.has("tool") ? actionObj.get("tool").getAsString() : "";
                    JsonObject params = actionObj.has("params") ? actionObj.getAsJsonObject("params") : new JsonObject();
                    if (!tool.isEmpty()) {
                        actions.add(new AgentAction(tool, params));
                    }
                }
            }

            String status = "done";
            if (root.has("status") && !root.get("status").isJsonNull()) {
                status = root.get("status").getAsString();
            }

            String nextStep = null;
            if (root.has("next_step") && !root.get("next_step").isJsonNull()) {
                nextStep = root.get("next_step").getAsString();
            }

            return new AgentResponse(response, actions, status, nextStep);
        } catch (Exception e) {
            // Graceful degradation: raw text as response, no actions
            return new AgentResponse(content.trim(), List.of());
        }
    }

    private static String extractJson(String content) {
        String trimmed = content.trim();

        // 1. If it already starts with '{', try as-is
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        // 2. Try to strip outer markdown fences using last occurrence for closing
        if (trimmed.startsWith("```")) {
            // Skip opening fence + optional language tag
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                String inner = trimmed.substring(firstNewline + 1);
                // Find the LAST ``` which is the actual closing fence
                int lastFence = inner.lastIndexOf("```");
                if (lastFence >= 0) {
                    return inner.substring(0, lastFence).trim();
                }
                return inner.trim();
            }
        }

        // 3. Fallback: find the outermost JSON object by brace matching
        int start = trimmed.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\' && inString) {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            return trimmed.substring(start, i + 1);
                        }
                    }
                }
            }
        }

        return trimmed;
    }
}
