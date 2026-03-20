package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.util.List;

public class JSGenerator {

    private final LLMProvider provider;
    private final ConfigManager config;
    private final PromptManager promptManager;

    public JSGenerator(LLMProvider provider, ConfigManager config, PromptManager promptManager) {
        this.provider = provider;
        this.config = config;
        this.promptManager = promptManager;
    }

    public GeneratedTool generate(AppModel appModel, List<Finding> findings, String toolType) throws LLMException {
        String promptKey = "object-sniffer".equals(toolType) ? "js_sniffer" : "js_generator";
        String systemPrompt = promptManager.getWithRules(promptKey);

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Generate a ").append(toolType).append(" JavaScript script.\n\n");

        // Rich application context
        userPrompt.append("## Application Context\n\n");
        userPrompt.append("- Endpoints: ").append(appModel.getEndpointCount()).append("\n");
        userPrompt.append("- Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        userPrompt.append("- Auth patterns: ").append(appModel.getAuthPatterns()).append("\n");
        userPrompt.append("- Cookies: ").append(String.join(", ", appModel.getCookies())).append("\n\n");

        // Detailed endpoints with params, headers, response codes
        userPrompt.append("## Endpoints (detailed)\n\n");
        appModel.getEndpoints().values().stream().limit(20).forEach(ep -> {
            userPrompt.append("### ").append(ep.getMethod()).append(" ").append(ep.getPathPattern()).append("\n");
            if (!ep.getQueryParams().isEmpty())
                userPrompt.append("  Query params: ").append(String.join(", ", ep.getQueryParams())).append("\n");
            if (ep.getAuthInfo() != null)
                userPrompt.append("  Auth: ").append(ep.getAuthInfo()).append("\n");
            if (!ep.getResponseCodes().isEmpty())
                userPrompt.append("  Response codes: ").append(ep.getResponseCodes()).append("\n");
            if (ep.getContentType() != null)
                userPrompt.append("  Content-Type: ").append(ep.getContentType()).append("\n");
            userPrompt.append("\n");
        });

        // Security findings
        if (!findings.isEmpty()) {
            userPrompt.append("## Security Findings\n\n");
            for (Finding f : findings) {
                userPrompt.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle())
                        .append(" (").append(String.join(", ", f.getAffectedEndpoints())).append(")\n");
                if (!f.getEvidence().isEmpty())
                    userPrompt.append("  Evidence: ").append(f.getEvidence(), 0, Math.min(200, f.getEvidence().length())).append("\n");
            }
            userPrompt.append("\n");
        }

        // Interesting patterns
        if (!appModel.getInterestingPatterns().isEmpty()) {
            userPrompt.append("## Interesting Patterns\n\n");
            appModel.getInterestingPatterns().stream().limit(20).forEach(p ->
                    userPrompt.append("- ").append(p).append("\n"));
            userPrompt.append("\n");
        }

        // THE KEY: actual JavaScript source code from the application
        appendJsSources(userPrompt, appModel);

        LLMResponse response = provider.chat(
                List.of(Message.system(systemPrompt), Message.user(userPrompt.toString())),
                config.getModel(),
                config.getMaxGenerationTokens()
        );

        String code = extractCode(response.getContent());
        String description = "Auto-generated " + toolType + " script based on observed traffic";
        if (response.isTruncated()) {
            description = "⚠ TRUNCATED (hit " + response.getOutputTokens() + " token limit — increase Max Generation Tokens in Config) — " + description;
        }
        return new GeneratedTool(
                toolType + " Script",
                GeneratedTool.ToolType.JS_SCRIPT,
                description,
                code,
                "javascript"
        );
    }

    /**
     * Append the application's actual JavaScript source code to the prompt.
     * Budget: ~30K chars total for JS sources to stay within token limits.
     */
    private void appendJsSources(StringBuilder sb, AppModel appModel) {
        var jsSources = appModel.getJsSources();
        if (jsSources.isEmpty()) {
            sb.append("## Application JavaScript\n\nNo JavaScript source code captured yet.\n\n");
            return;
        }

        sb.append("## Application JavaScript Source Code (").append(jsSources.size()).append(" files captured)\n\n");
        sb.append("IMPORTANT: Analyze this code to understand the application's actual variables, functions, API calls, ");
        sb.append("storage patterns, auth flows, and data structures. Your generated script must target THESE specific ");
        sb.append("objects and patterns — not generic ones.\n\n");

        int totalBudget = 30_000;
        int used = 0;

        for (var entry : jsSources.entrySet()) {
            if (used >= totalBudget) {
                sb.append("// ... ").append(jsSources.size()).append(" files total, remaining omitted for token budget\n\n");
                break;
            }

            String url = entry.getKey();
            String source = entry.getValue();

            // Extract just the filename from URL
            String filename = url;
            int lastSlash = url.lastIndexOf('/');
            if (lastSlash >= 0) filename = url.substring(lastSlash + 1);
            int queryIdx = filename.indexOf('?');
            if (queryIdx >= 0) filename = filename.substring(0, queryIdx);

            int available = totalBudget - used;
            String content = source.length() > available
                    ? source.substring(0, available) + "\n// [truncated by Forja]"
                    : source;

            sb.append("### ").append(filename).append("\n");
            sb.append("// Source: ").append(url).append("\n");
            sb.append("```javascript\n");
            sb.append(content).append("\n");
            sb.append("```\n\n");

            used += content.length();
        }
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

}
