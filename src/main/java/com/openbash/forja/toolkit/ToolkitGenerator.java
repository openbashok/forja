package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.analysis.Severity;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ToolkitGenerator {

    private final LLMProvider provider;
    private final ConfigManager config;

    public ToolkitGenerator(LLMProvider provider, ConfigManager config) {
        this.provider = provider;
        this.config = config;
    }

    public List<GeneratedTool> generateAll(AppModel appModel, List<Finding> findings) throws LLMException {
        List<GeneratedTool> tools = new ArrayList<>();
        JSGenerator jsGen = new JSGenerator(provider, config);
        BurpPluginGenerator burpGen = new BurpPluginGenerator(provider, config);

        boolean hasAuthFindings = findings.stream()
                .anyMatch(f -> f.getTitle().toLowerCase().contains("auth")
                        || f.getTitle().toLowerCase().contains("session")
                        || f.getTitle().toLowerCase().contains("jwt"));
        boolean hasIdorFindings = findings.stream()
                .anyMatch(f -> f.getTitle().toLowerCase().contains("idor")
                        || f.getTitle().toLowerCase().contains("access control"));
        boolean hasCritOrHigh = findings.stream()
                .anyMatch(f -> f.getSeverity() == Severity.CRITICAL || f.getSeverity() == Severity.HIGH);

        // Always generate a traffic sniffer
        tools.add(jsGen.generate(appModel, findings, "traffic-sniffer"));

        // Generate PoC for critical/high findings
        if (hasCritOrHigh) {
            tools.add(jsGen.generate(appModel, findings, "poc-exploit"));
        }

        // JWT manipulator if JWT detected
        if (!appModel.getAuthPatterns().isEmpty() || hasAuthFindings) {
            tools.add(jsGen.generate(appModel, findings, "jwt-manipulator"));
        }

        // Auth tester Burp extension
        if (hasAuthFindings || !appModel.getAuthPatterns().isEmpty()) {
            tools.add(burpGen.generate(appModel, findings, "auth-tester"));
        }

        // IDOR scanner
        if (hasIdorFindings || appModel.getInterestingPatterns().stream().anyMatch(p -> p.contains("Sequential ID"))) {
            tools.add(burpGen.generate(appModel, findings, "idor-scanner"));
        }

        // Parameter fuzzer for apps with many params
        long paramEndpoints = appModel.getEndpoints().values().stream()
                .filter(e -> !e.getQueryParams().isEmpty())
                .count();
        if (paramEndpoints > 3) {
            tools.add(burpGen.generate(appModel, findings, "param-fuzzer"));
        }

        return tools;
    }

    public GeneratedTool generateSingle(AppModel appModel, List<Finding> findings,
                                         String type, String toolType) throws LLMException {
        if ("js".equals(type)) {
            return new JSGenerator(provider, config).generate(appModel, findings, toolType);
        } else {
            return new BurpPluginGenerator(provider, config).generate(appModel, findings, toolType);
        }
    }

    /**
     * Generate a tool from a free-form user prompt.
     * The LLM receives the full application context + findings + the user's instructions.
     */
    public GeneratedTool generateFromPrompt(AppModel appModel, List<Finding> findings,
                                             String userPrompt) throws LLMException {
        ContextBuilder contextBuilder = new ContextBuilder(6000);
        String appContext = contextBuilder.buildContext(appModel);

        StringBuilder prompt = new StringBuilder();
        prompt.append("## User Request\n\n");
        prompt.append(userPrompt).append("\n\n");
        prompt.append("## Application Context\n\n");
        prompt.append(appContext);

        if (!findings.isEmpty()) {
            prompt.append("\n## Security Findings\n\n");
            for (Finding f : findings) {
                prompt.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle());
                prompt.append(" — ").append(f.getDescription());
                if (!f.getAffectedEndpoints().isEmpty()) {
                    prompt.append(" (").append(String.join(", ", f.getAffectedEndpoints())).append(")");
                }
                prompt.append("\n");
            }
        }

        String systemPrompt = "You are a security tool developer working with a penetration tester.\n"
                + "The user will ask you to generate a specific security testing tool.\n"
                + "You have full context of the target application (endpoints, auth, tech stack, vulnerabilities).\n\n"
                + "Rules:\n"
                + "- Generate exactly what the user asks for\n"
                + "- Use real endpoints, parameters, and patterns from the application context\n"
                + "- For JavaScript: use modern ES6+ with fetch API, runnable in browser console or Node.js\n"
                + "- For Java/Burp extensions: use Montoya API (burp.api.montoya.*)\n"
                + "- For Python: use requests library\n"
                + "- Include clear comments and configurable variables at the top\n"
                + "- Handle errors gracefully\n\n"
                + "Output the complete code wrapped in a fenced code block with the language tag "
                + "(```javascript, ```java, ```python, etc.).\n"
                + "Before the code block, write a one-line description of the tool.";

        LLMResponse response = provider.chat(
                List.of(Message.system(systemPrompt), Message.user(prompt.toString())),
                config.getModel(),
                8192
        );

        String content = response.getContent();
        String code = extractCode(content);
        String language = detectLanguage(content);
        String description = extractDescription(content);

        return new GeneratedTool(
                "Custom Tool",
                language.equals("java") ? GeneratedTool.ToolType.BURP_EXTENSION : GeneratedTool.ToolType.JS_SCRIPT,
                description,
                code,
                language
        );
    }

    private String extractCode(String content) {
        // Try to find a fenced code block with language tag
        for (String lang : new String[]{"javascript", "js", "java", "python", "py", "bash", "sh", "html"}) {
            String tag = "```" + lang;
            if (content.contains(tag)) {
                String code = content.substring(content.indexOf(tag) + tag.length());
                int end = code.indexOf("```");
                return (end > 0 ? code.substring(0, end) : code).trim();
            }
        }
        // Generic code block
        if (content.contains("```")) {
            String code = content.substring(content.indexOf("```") + 3);
            // Skip optional language identifier on same line
            int newline = code.indexOf('\n');
            if (newline >= 0 && newline < 20) {
                code = code.substring(newline + 1);
            }
            int end = code.indexOf("```");
            return (end > 0 ? code.substring(0, end) : code).trim();
        }
        return content;
    }

    private String detectLanguage(String content) {
        if (content.contains("```java")) return "java";
        if (content.contains("```python") || content.contains("```py")) return "python";
        if (content.contains("```bash") || content.contains("```sh")) return "bash";
        if (content.contains("```html")) return "html";
        return "javascript";
    }

    private String extractDescription(String content) {
        // Get the text before the first code block as description
        int codeStart = content.indexOf("```");
        if (codeStart > 0) {
            String before = content.substring(0, codeStart).trim();
            // Take last non-empty line before the code block
            String[] lines = before.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    return line;
                }
            }
        }
        return "Custom generated tool";
    }
}
