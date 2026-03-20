package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.analysis.Severity;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ToolkitGenerator {

    private final LLMProvider provider;
    private final ConfigManager config;
    private final PromptManager promptManager;

    public ToolkitGenerator(LLMProvider provider, ConfigManager config, PromptManager promptManager) {
        this.provider = provider;
        this.config = config;
        this.promptManager = promptManager;
    }

    public List<GeneratedTool> generateAll(AppModel appModel, List<Finding> findings) throws LLMException {
        List<GeneratedTool> tools = new ArrayList<>();
        JSGenerator jsGen = new JSGenerator(provider, config, promptManager);
        BurpPluginGenerator burpGen = new BurpPluginGenerator(provider, config, promptManager);

        boolean hasAuthFindings = findings.stream()
                .anyMatch(f -> f.getTitle().toLowerCase().contains("auth")
                        || f.getTitle().toLowerCase().contains("session")
                        || f.getTitle().toLowerCase().contains("jwt"));
        boolean hasIdorFindings = findings.stream()
                .anyMatch(f -> f.getTitle().toLowerCase().contains("idor")
                        || f.getTitle().toLowerCase().contains("access control"));
        boolean hasCritOrHigh = findings.stream()
                .anyMatch(f -> f.getSeverity() == Severity.CRITICAL || f.getSeverity() == Severity.HIGH);

        // Always generate a traffic sniffer and object sniffer
        tools.add(jsGen.generate(appModel, findings, "traffic-sniffer"));
        tools.add(jsGen.generate(appModel, findings, "object-sniffer"));

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
            return new JSGenerator(provider, config, promptManager).generate(appModel, findings, toolType);
        } else {
            return new BurpPluginGenerator(provider, config, promptManager).generate(appModel, findings, toolType);
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
                + "- For JavaScript: use modern ES6+ with fetch API, runnable in browser console.\n"
                + "  IMPORTANT: Every JS script MUST create a visible floating panel overlay (Burp Suite dark style).\n"
                + "  Panel: position:fixed, background:#2b2b2b, color:#d4d4d4, border:1px solid #555, z-index:999999.\n"
                + "  Title bar: background:#3c3f41, draggable, with 'Forja' in orange (#e8a238) + tool name, minimize/close buttons.\n"
                + "  Implement real drag (mousedown/mousemove), resize:both CSS, status bar at bottom.\n"
                + "  Use forjaLog(msg,type) with types: info=#8acdff, success=#6a9955, warn=#e8a238, error=#f44747.\n"
                + "  Keep code concise (under 200 lines).\n"
                + "- For Burp extensions: use Jython with the legacy Burp API (IBurpExtender, IHttpListener, etc.). "
                + "NEVER generate Java Burp extensions — Jython .py files load directly in Burp without compilation. "
                + "CRITICAL: Jython is PYTHON 2.7 — use print as statement (print \"msg\"), "
                + "use sys.stderr.write() for errors (NOT print(..., file=sys.stderr)), "
                + "NO f-strings, NO type hints, NO Python 3 syntax.\n"
                + "- For standalone Python scripts (not Burp): use Python 3 with requests library\n"
                + "- Include clear comments and configurable variables at the top\n"
                + "- Handle errors gracefully\n"
                + "- Keep code concise and self-contained in a single file — NO placeholder functions or TODOs\n\n"
                + "Output the complete code wrapped in a fenced code block with the language tag "
                + "(```javascript, ```python, etc.).\n"
                + "Before the code block, write a one-line description of the tool.\n\n"
                + promptManager.get("global_rules");

        LLMResponse response = provider.chat(
                List.of(Message.system(systemPrompt), Message.user(prompt.toString())),
                config.getModel(),
                config.getMaxGenerationTokens()
        );

        String content = response.getContent();
        String code = extractCode(content);
        String language = detectLanguage(content);
        String description = extractDescription(content);
        if (response.isTruncated()) {
            description = "⚠ TRUNCATED (hit " + response.getOutputTokens() + " token limit — increase Max Generation Tokens in Config) — " + description;
        }

        GeneratedTool.ToolType toolType;
        if (content.contains("IBurpExtender") || content.contains("registerExtenderCallbacks")) {
            toolType = GeneratedTool.ToolType.BURP_EXTENSION;
        } else if (language.equals("javascript")) {
            toolType = GeneratedTool.ToolType.JS_SCRIPT;
        } else {
            toolType = GeneratedTool.ToolType.JS_SCRIPT; // Python scripts, bash, etc.
        }

        String name = extractName(content, description);
        return new GeneratedTool(
                name,
                toolType,
                description,
                code,
                language
        );
    }

    /**
     * Extract a short tool name from the LLM response.
     * Looks for a title/heading or derives from the description.
     */
    private String extractName(String content, String description) {
        int codeStart = content.indexOf("```");
        if (codeStart > 0) {
            String before = content.substring(0, codeStart).trim();
            String[] lines = before.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                // Look for markdown headings: # Title or ## Title
                if (trimmed.startsWith("#")) {
                    String heading = trimmed.replaceFirst("^#+\\s*", "").trim();
                    if (!heading.isEmpty() && heading.length() <= 80) return heading;
                }
                // Look for "Title: ..." or "Tool Name: ..." patterns
                if (trimmed.matches("(?i)(tool\\s*name|name|title)\\s*:.*")) {
                    String val = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    if (!val.isEmpty() && val.length() <= 80) return val;
                }
            }
        }

        // Derive from description: take first sentence, cap at 60 chars
        if (description != null && !description.isEmpty() && !description.equals("Custom generated tool")) {
            String name = description;
            // Remove leading markers like "**Title**" markdown bold
            name = name.replaceAll("\\*\\*", "").trim();
            // Truncate at first period or dash
            int dot = name.indexOf(". ");
            if (dot > 0 && dot < 60) name = name.substring(0, dot);
            int dash = name.indexOf(" — ");
            if (dash > 0 && dash < 60) name = name.substring(0, dash);
            int hyphen = name.indexOf(" - ");
            if (hyphen > 0 && hyphen < 60) name = name.substring(0, hyphen);
            if (name.length() > 60) name = name.substring(0, 57) + "...";
            return name;
        }
        return "Generated Tool";
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
