package com.openbash.forja.agent;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;

public class AgentResponse {

    private final String response;
    private final List<AgentAction> actions;

    public AgentResponse(String response, List<AgentAction> actions) {
        this.response = response;
        this.actions = actions;
    }

    public String getResponse() { return response; }
    public List<AgentAction> getActions() { return actions; }

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

            return new AgentResponse(response, actions);
        } catch (Exception e) {
            // Graceful degradation: raw text as response, no actions
            return new AgentResponse(content.trim(), List.of());
        }
    }

    private static String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.contains("```json")) {
            trimmed = trimmed.substring(trimmed.indexOf("```json") + 7);
            trimmed = trimmed.substring(0, trimmed.indexOf("```"));
        } else if (trimmed.contains("```")) {
            trimmed = trimmed.substring(trimmed.indexOf("```") + 3);
            // Skip optional language tag on same line
            int nl = trimmed.indexOf('\n');
            if (nl >= 0 && nl < 20) {
                trimmed = trimmed.substring(nl + 1);
            }
            trimmed = trimmed.substring(0, trimmed.indexOf("```"));
        }
        return trimmed.trim();
    }
}
