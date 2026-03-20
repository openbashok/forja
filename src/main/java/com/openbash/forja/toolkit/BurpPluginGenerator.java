package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.CryptoDetector;

import java.util.List;

/**
 * Generates Burp Suite extensions in Jython (Python).
 * Jython extensions are loaded directly as .py files — no compilation or JAR packaging needed.
 */
public class BurpPluginGenerator {

    private final LLMProvider provider;
    private final ConfigManager config;
    private final PromptManager promptManager;

    public BurpPluginGenerator(LLMProvider provider, ConfigManager config, PromptManager promptManager) {
        this.provider = provider;
        this.config = config;
        this.promptManager = promptManager;
    }

    public GeneratedTool generate(AppModel appModel, List<Finding> findings, String toolType) throws LLMException {
        String systemPrompt = promptManager.getWithRules("burp_generator");

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Generate a ").append(toolType).append(" Burp Suite extension in Python (Jython).\n\n");

        // Full application context
        userPrompt.append("## Application Context\n\n");
        userPrompt.append("- Endpoints: ").append(appModel.getEndpointCount()).append("\n");
        userPrompt.append("- Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        userPrompt.append("- Auth patterns: ").append(appModel.getAuthPatterns()).append("\n");
        userPrompt.append("- Cookies: ").append(String.join(", ", appModel.getCookies())).append("\n\n");

        // Full security findings
        if (!findings.isEmpty()) {
            userPrompt.append("## Security Findings\n\n");
            for (Finding f : findings) {
                userPrompt.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append("\n");
                userPrompt.append("  ").append(f.getDescription()).append("\n");
                userPrompt.append("  Endpoints: ").append(String.join(", ", f.getAffectedEndpoints())).append("\n");
                if (!f.getEvidence().isEmpty())
                    userPrompt.append("  Evidence: ").append(f.getEvidence()).append("\n");
                if (!f.getRecommendation().isEmpty())
                    userPrompt.append("  Recommendation: ").append(f.getRecommendation()).append("\n");
            }
            userPrompt.append("\n");
        }

        // Crypto findings
        var cryptoFindings = appModel.getCryptoFindings();
        if (!cryptoFindings.isEmpty()) {
            userPrompt.append("## Detected Cryptographic Patterns\n\n");
            for (CryptoDetector.CryptoFinding cf : cryptoFindings) {
                userPrompt.append("- [").append(cf.getType()).append("] ").append(cf.getDescription()).append("\n");
                userPrompt.append("  URL: ").append(cf.getUrl()).append("\n");
                userPrompt.append("  Sample: ").append(cf.getSample()).append("\n");
            }
            userPrompt.append("\n");
        }

        // ALL endpoints with full samples — no limits
        userPrompt.append("## Endpoints (full details)\n\n");
        for (var ep : appModel.getEndpoints().values()) {
            userPrompt.append("### ").append(ep.getMethod()).append(" ").append(ep.getPathPattern());
            if (ep.getAuthInfo() != null) userPrompt.append(" [AUTH: ").append(ep.getAuthInfo().getType()).append("]");
            userPrompt.append("\n");
            if (!ep.getQueryParams().isEmpty())
                userPrompt.append("  Query params: ").append(String.join(", ", ep.getQueryParams())).append("\n");
            if (!ep.getResponseCodes().isEmpty())
                userPrompt.append("  Response codes: ").append(ep.getResponseCodes()).append("\n");
            if (ep.getContentType() != null)
                userPrompt.append("  Content-Type: ").append(ep.getContentType()).append("\n");
            if (ep.getSampleRequest() != null) {
                userPrompt.append("  Sample request:\n```\n").append(ep.getSampleRequest()).append("\n```\n");
            }
            if (ep.getSampleResponse() != null) {
                userPrompt.append("  Sample response:\n```\n").append(ep.getSampleResponse()).append("\n```\n");
            }
            userPrompt.append("\n");
        }

        // Interesting patterns
        if (!appModel.getInterestingPatterns().isEmpty()) {
            userPrompt.append("## Interesting Patterns\n\n");
            appModel.getInterestingPatterns().forEach(p ->
                    userPrompt.append("- ").append(p).append("\n"));
            userPrompt.append("\n");
        }

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

}
