package com.openbash.forja.agent;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // 1. Try strict JSON parse
        try {
            String json = extractJson(content);
            AgentResponse result = parseJsonObject(JsonParser.parseString(json).getAsJsonObject());
            if (result != null) return result;
        } catch (Exception ignored) {}

        // 2. JSON was malformed (e.g. JS expressions inside strings).
        //    Try to sanitize common issues and re-parse.
        try {
            String sanitized = sanitizeJson(extractJson(content));
            AgentResponse result = parseJsonObject(JsonParser.parseString(sanitized).getAsJsonObject());
            if (result != null) return result;
        } catch (Exception ignored) {}

        // 3. JSON completely broken — extract what we can with regex
        String responseText = regexExtract(content, "\"response\"\\s*:\\s*\"", "\"");
        if (responseText != null) {
            // Try to extract actions too
            List<AgentAction> actions = regexExtractActions(content);
            return new AgentResponse(unescapeJson(responseText), actions);
        }

        // 4. Last resort — strip JSON wrapper if visible, show clean text
        return new AgentResponse(stripJsonWrapper(content.trim()), List.of());
    }

    private static AgentResponse parseJsonObject(JsonObject root) {
        String response = "";
        if (root.has("response") && !root.get("response").isJsonNull()) {
            response = root.get("response").getAsString();
        }

        List<AgentAction> actions = new ArrayList<>();
        if (root.has("actions") && root.get("actions").isJsonArray()) {
            for (JsonElement elem : root.getAsJsonArray("actions")) {
                try {
                    JsonObject actionObj = elem.getAsJsonObject();
                    String tool = actionObj.has("tool") ? actionObj.get("tool").getAsString() : "";
                    JsonObject params = actionObj.has("params") ? actionObj.getAsJsonObject("params") : new JsonObject();
                    if (!tool.isEmpty()) {
                        actions.add(new AgentAction(tool, params));
                    }
                } catch (Exception ignored) {}
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
    }

    /**
     * Fix common LLM JSON mistakes: JS expressions in strings, trailing commas, etc.
     */
    private static String sanitizeJson(String json) {
        // Remove JS string concatenation: " + expression + "  →  (remove the expression)
        String sanitized = json.replaceAll("\"\\s*\\+\\s*[^\"]+?\\s*\\+\\s*\"", "");
        // Trailing commas before } or ]
        sanitized = sanitized.replaceAll(",\\s*([}\\]])", "$1");
        return sanitized;
    }

    /**
     * Extract a JSON string value using regex — works even when JSON is broken.
     * Finds the value after the key pattern and reads until an unescaped quote.
     */
    private static String regexExtract(String content, String keyPattern, String endDelimiter) {
        Pattern p = Pattern.compile(keyPattern, Pattern.DOTALL);
        Matcher m = p.matcher(content);
        if (!m.find()) return null;

        int start = m.end();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                sb.append(c);
                continue;
            }
            // Check for end delimiter (unescaped quote followed by comma, }, or whitespace)
            if (c == '"') {
                // Look ahead: is this likely the closing quote of "response"?
                if (i + 1 < content.length()) {
                    char next = content.charAt(i + 1);
                    if (next == ',' || next == '}' || next == '\n' || next == '\r') {
                        return sb.toString();
                    }
                } else {
                    return sb.toString();
                }
                // Otherwise it might be a quote inside the value (malformed JSON)
                sb.append(c);
                continue;
            }
            sb.append(c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Try to extract actions array with regex when JSON parsing fails.
     */
    private static List<AgentAction> regexExtractActions(String content) {
        List<AgentAction> actions = new ArrayList<>();
        // Find "tool": "xxx" patterns
        Pattern toolPattern = Pattern.compile("\"tool\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = toolPattern.matcher(content);
        while (m.find()) {
            String tool = m.group(1);
            if (!tool.equals("tool_name") && !tool.isEmpty()) {
                // Try to find params object nearby
                int searchStart = m.end();
                int paramsIdx = content.indexOf("\"params\"", searchStart);
                JsonObject params = new JsonObject();
                if (paramsIdx >= 0 && paramsIdx < searchStart + 200) {
                    // Try to extract the params object
                    int braceStart = content.indexOf('{', paramsIdx);
                    if (braceStart >= 0) {
                        String paramsJson = extractBraceBlock(content, braceStart);
                        if (paramsJson != null) {
                            try {
                                params = JsonParser.parseString(paramsJson).getAsJsonObject();
                            } catch (Exception ignored) {}
                        }
                    }
                }
                actions.add(new AgentAction(tool, params));
            }
        }
        return actions;
    }

    /**
     * Extract a brace-delimited block from content starting at pos.
     */
    private static String extractBraceBlock(String content, int pos) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = pos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') inStr = !inStr;
            else if (!inStr) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return content.substring(pos, i + 1);
                }
            }
        }
        return null;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * If the raw content looks like our JSON response format, strip the wrapper
     * and just show the "response" value as clean text.
     */
    private static String stripJsonWrapper(String content) {
        // If it looks like {"response": "...", detect and extract just the text
        if (content.startsWith("{") && content.contains("\"response\"")) {
            String extracted = regexExtract(content, "\"response\"\\s*:\\s*\"", "\"");
            if (extracted != null && extracted.length() > 10) {
                return unescapeJson(extracted);
            }
        }
        // Strip markdown json fences if present
        if (content.startsWith("```")) {
            int firstNl = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstNl >= 0 && lastFence > firstNl) {
                String inner = content.substring(firstNl + 1, lastFence).trim();
                // Recurse to strip JSON wrapper inside fences
                return stripJsonWrapper(inner);
            }
        }
        return content;
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
