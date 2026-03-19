package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JSGenerator {

    private final LLMProvider provider;
    private final ConfigManager config;

    public JSGenerator(LLMProvider provider, ConfigManager config) {
        this.provider = provider;
        this.config = config;
    }

    public GeneratedTool generate(AppModel appModel, List<Finding> findings, String toolType) throws LLMException {
        String systemPrompt = loadPrompt("prompts/js_generator.txt");

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Generate a ").append(toolType).append(" JavaScript script.\n\n");
        userPrompt.append("Application context:\n");
        userPrompt.append("- Endpoints: ").append(appModel.getEndpointCount()).append("\n");
        userPrompt.append("- Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        userPrompt.append("- Auth patterns: ").append(appModel.getAuthPatterns()).append("\n\n");

        if (!findings.isEmpty()) {
            userPrompt.append("Security findings:\n");
            for (Finding f : findings) {
                userPrompt.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle())
                        .append(" (").append(String.join(", ", f.getAffectedEndpoints())).append(")\n");
            }
        }

        // Include sample endpoints
        userPrompt.append("\nEndpoint samples:\n");
        appModel.getEndpoints().values().stream().limit(10).forEach(ep -> {
            userPrompt.append("- ").append(ep.getMethod()).append(" ").append(ep.getPathPattern()).append("\n");
            if (ep.getSampleRequest() != null) {
                userPrompt.append("  Request: ").append(ep.getSampleRequest().substring(0, Math.min(500, ep.getSampleRequest().length()))).append("\n");
            }
        });

        LLMResponse response = provider.chat(
                List.of(Message.system(systemPrompt), Message.user(userPrompt.toString())),
                config.getModel(),
                4096
        );

        String code = extractCode(response.getContent());
        return new GeneratedTool(
                toolType + " Script",
                GeneratedTool.ToolType.JS_SCRIPT,
                "Auto-generated " + toolType + " script based on observed traffic",
                code,
                "javascript"
        );
    }

    private String extractCode(String content) {
        if (content.contains("```javascript")) {
            String code = content.substring(content.indexOf("```javascript") + 13);
            int end = code.indexOf("```");
            return end > 0 ? code.substring(0, end).trim() : code.trim();
        }
        if (content.contains("```js")) {
            String code = content.substring(content.indexOf("```js") + 5);
            int end = code.indexOf("```");
            return end > 0 ? code.substring(0, end).trim() : code.trim();
        }
        if (content.contains("```")) {
            String code = content.substring(content.indexOf("```") + 3);
            int end = code.indexOf("```");
            return end > 0 ? code.substring(0, end).trim() : code.trim();
        }
        return content;
    }

    private String loadPrompt(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return "You generate JavaScript security testing scripts.";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "You generate JavaScript security testing scripts.";
        }
    }
}
