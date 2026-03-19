package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Generates Burp Suite extensions in Jython (Python).
 * Jython extensions are loaded directly as .py files — no compilation or JAR packaging needed.
 */
public class BurpPluginGenerator {

    private final LLMProvider provider;
    private final ConfigManager config;

    public BurpPluginGenerator(LLMProvider provider, ConfigManager config) {
        this.provider = provider;
        this.config = config;
    }

    public GeneratedTool generate(AppModel appModel, List<Finding> findings, String toolType) throws LLMException {
        String systemPrompt = loadPrompt("prompts/burp_generator.txt");

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Generate a ").append(toolType).append(" Burp Suite extension in Python (Jython).\n\n");
        userPrompt.append("Application context:\n");
        userPrompt.append("- Endpoints: ").append(appModel.getEndpointCount()).append("\n");
        userPrompt.append("- Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        userPrompt.append("- Auth patterns: ").append(appModel.getAuthPatterns()).append("\n\n");

        if (!findings.isEmpty()) {
            userPrompt.append("Security findings to target:\n");
            for (Finding f : findings) {
                userPrompt.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append("\n");
                userPrompt.append("  ").append(f.getDescription()).append("\n");
                userPrompt.append("  Endpoints: ").append(String.join(", ", f.getAffectedEndpoints())).append("\n");
            }
        }

        userPrompt.append("\nEndpoint samples:\n");
        appModel.getEndpoints().values().stream().limit(10).forEach(ep -> {
            userPrompt.append("- ").append(ep.getMethod()).append(" ").append(ep.getPathPattern());
            if (ep.getAuthInfo() != null) userPrompt.append(" [AUTH: ").append(ep.getAuthInfo().getType()).append("]");
            userPrompt.append("\n");
            if (ep.getSampleRequest() != null) {
                userPrompt.append("  Sample: ").append(
                        ep.getSampleRequest().substring(0, Math.min(300, ep.getSampleRequest().length()))
                ).append("\n");
            }
        });

        LLMResponse response = provider.chat(
                List.of(Message.system(systemPrompt), Message.user(userPrompt.toString())),
                config.getModel(),
                config.getMaxGenerationTokens()
        );

        String code = extractCode(response.getContent());
        String description = "Jython Burp extension: " + toolType + " (load as .py in Burp > Extensions)";
        if (response.isTruncated()) {
            description = "⚠ TRUNCATED (hit " + response.getOutputTokens() + " token limit — increase Max Generation Tokens in Config) — " + description;
        }
        return new GeneratedTool(
                toolType + " Extension",
                GeneratedTool.ToolType.BURP_EXTENSION,
                description,
                code,
                "python"
        );
    }

    private String extractCode(String content) {
        if (content.contains("```python")) {
            String code = content.substring(content.indexOf("```python") + 9);
            int end = code.indexOf("```");
            return end > 0 ? code.substring(0, end).trim() : code.trim();
        }
        if (content.contains("```py")) {
            String code = content.substring(content.indexOf("```py") + 5);
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
            if (is == null) return "You generate Burp Suite extensions in Jython (Python).";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "You generate Burp Suite extensions in Jython (Python).";
        }
    }
}
