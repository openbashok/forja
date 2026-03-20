package com.openbash.forja.toolkit;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.CryptoDetector;
import com.openbash.forja.traffic.ResourceStore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Integrates with Claude Code CLI (claude -p) for deep traffic analysis.
 * Follows the same pattern as WebAudit: prepare context on disk,
 * launch Claude Code pointing at the resources, collect generated artifacts.
 */
public class ClaudeCodeIntegration {

    private final ConfigManager config;
    private final PromptManager promptManager;
    private final AppModel appModel;
    private final ResourceStore resourceStore;
    private volatile Process runningProcess;

    public ClaudeCodeIntegration(ConfigManager config, PromptManager promptManager,
                                  AppModel appModel, ResourceStore resourceStore) {
        this.config = config;
        this.promptManager = promptManager;
        this.appModel = appModel;
        this.resourceStore = resourceStore;
    }

    /**
     * Run Claude Code deep analysis.
     * @param findings existing findings from the LLM analysis (passed as context)
     * @param userPrompt optional extra instructions from the user
     * @param outputCallback receives streaming output lines (called from background thread)
     * @param completionCallback called when done with the list of generated artifacts
     * @return the Process (for stopping)
     */
    public Process run(List<Finding> findings, String userPrompt,
                       Consumer<String> outputCallback,
                       Consumer<List<GeneratedTool>> completionCallback) throws IOException {

        Path workDir = Path.of(config.getOutputDir());
        Files.createDirectories(workDir);

        // 1. Generate CLAUDE.md with full traffic context
        outputCallback.accept("[Forja] Generating CLAUDE.md with traffic context...");
        generateClaudeMd(workDir, findings);

        // 2. Find claude binary
        String claudeBin = findClaudeBinary();
        if (claudeBin == null) {
            outputCallback.accept("[Error] Claude Code CLI not found. Install: npm install -g @anthropic-ai/claude-code");
            completionCallback.accept(List.of());
            return null;
        }
        outputCallback.accept("[Forja] Found Claude Code: " + claudeBin);

        // 3. Load system prompt
        String systemPrompt = loadSystemPrompt();

        // 4. Build user prompt
        String fullUserPrompt = buildUserPrompt(userPrompt, workDir);

        // 5. Build command
        List<String> cmd = new ArrayList<>();
        cmd.add(claudeBin);
        cmd.add("-p");
        cmd.add(fullUserPrompt);
        cmd.add("--system-prompt");
        cmd.add(systemPrompt);

        // Use the configured model
        String model = config.getModel();
        if (model != null && !model.isEmpty()) {
            cmd.add("--model");
            cmd.add(model);
        }

        // Budget
        double budget = config.getBudget();
        if (budget > 0) {
            cmd.add("--max-budget-usd");
            cmd.add(String.valueOf(budget));
        }

        cmd.add("--dangerously-skip-permissions");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        outputCallback.accept("[Forja] Launching: claude -p ...");
        outputCallback.accept("[Forja] Working directory: " + workDir);
        outputCallback.accept("[Forja] Model: " + model + " | Budget: $" + budget);
        outputCallback.accept("");

        // 6. Launch process
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        // Set environment
        Map<String, String> env = pb.environment();
        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            env.put("ANTHROPIC_API_KEY", apiKey);
        }
        // Max output tokens
        env.put("CLAUDE_CODE_MAX_OUTPUT_TOKENS", "128000");

        Process proc = pb.start();
        this.runningProcess = proc;

        // Stream stdout in background
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String parsed = parseStreamJson(line);
                    if (parsed != null) {
                        outputCallback.accept(parsed);
                    }
                }
            } catch (IOException ignored) {}
        }, "claude-code-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        // Stream stderr in background
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputCallback.accept("[stderr] " + line);
                }
            } catch (IOException ignored) {}
        }, "claude-code-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        // Wait for completion in background
        Thread waitThread = new Thread(() -> {
            try {
                int exitCode = proc.waitFor();
                stdoutThread.join(5000);
                stderrThread.join(5000);

                outputCallback.accept("");
                outputCallback.accept("[Forja] Claude Code finished with exit code: " + exitCode);

                // Collect generated artifacts
                List<GeneratedTool> tools = collectArtifacts(workDir);
                if (tools.isEmpty()) {
                    outputCallback.accept("[Forja] No artifacts generated. Check the output above for errors.");
                } else {
                    outputCallback.accept("[Forja] Generated " + tools.size() + " artifacts:");
                    for (GeneratedTool t : tools) {
                        outputCallback.accept("  - " + t.getName() + " (" + t.getLanguage() + ")");
                    }
                }
                completionCallback.accept(tools);
            } catch (InterruptedException e) {
                outputCallback.accept("[Forja] Process interrupted.");
                completionCallback.accept(List.of());
            } finally {
                runningProcess = null;
            }
        }, "claude-code-waiter");
        waitThread.setDaemon(true);
        waitThread.start();

        return proc;
    }

    public void stop() {
        Process proc = runningProcess;
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
        }
        runningProcess = null;
    }

    public boolean isRunning() {
        Process proc = runningProcess;
        return proc != null && proc.isAlive();
    }

    // ========== Context Generation ==========

    private void generateClaudeMd(Path workDir, List<Finding> findings) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Forja — Traffic Analysis Context\n\n");
        md.append("This directory contains HTTP traffic captured by Burp Suite via Forja.\n\n");

        md.append("## Application Overview\n\n");
        md.append("- Endpoints captured: ").append(appModel.getEndpointCount()).append("\n");
        if (!appModel.getTechStack().isEmpty()) {
            md.append("- Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        }
        if (!appModel.getAuthPatterns().isEmpty()) {
            md.append("- Auth patterns: ").append(
                    appModel.getAuthPatterns().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining("; "))).append("\n");
        }
        if (!appModel.getCookies().isEmpty()) {
            md.append("- Cookies: ").append(String.join(", ", appModel.getCookies())).append("\n");
        }

        // Resource stats
        md.append("\n## Captured Resources\n\n");
        md.append("- JavaScript files: ").append(resourceStore.getJsCount()).append(" (in `captured/js/`)\n");
        md.append("- HTML pages: ").append(resourceStore.getHtmlCount()).append(" (in `captured/html/`)\n");
        md.append("- Request/Response pairs: ").append(resourceStore.getRequestCount()).append(" (in `captured/requests/`)\n");
        md.append("- Crypto samples: ").append(resourceStore.getCryptoCount()).append(" (in `captured/crypto/`)\n");

        // Endpoints
        md.append("\n## Endpoints\n\n");
        for (var ep : appModel.getEndpoints().values()) {
            md.append("- **").append(ep.getMethod()).append(" ").append(ep.getPathPattern()).append("**");
            if (ep.getAuthInfo() != null) md.append(" [AUTH]");
            if (!ep.getQueryParams().isEmpty())
                md.append(" params: ").append(String.join(", ", ep.getQueryParams()));
            md.append(" (seen ").append(ep.getTimesSeen()).append("x)");
            md.append("\n");
        }

        // Interesting patterns
        if (!appModel.getInterestingPatterns().isEmpty()) {
            md.append("\n## Interesting Patterns\n\n");
            for (String p : appModel.getInterestingPatterns()) {
                md.append("- ").append(p).append("\n");
            }
        }

        // Crypto findings
        var cryptoFindings = appModel.getCryptoFindings();
        if (!cryptoFindings.isEmpty()) {
            md.append("\n## Detected Cryptographic Patterns\n\n");
            for (CryptoDetector.CryptoFinding cf : cryptoFindings) {
                md.append("- **[").append(cf.getType()).append("]** ").append(cf.getDescription()).append("\n");
                md.append("  URL: ").append(cf.getUrl()).append("\n");
                md.append("  Sample: `").append(cf.getSample()).append("`\n");
            }
        }

        // Existing findings
        if (findings != null && !findings.isEmpty()) {
            md.append("\n## Previous Analysis Findings\n\n");
            for (Finding f : findings) {
                md.append("- [").append(f.getSeverity()).append("] **").append(f.getTitle()).append("**");
                if (!f.getAffectedEndpoints().isEmpty()) {
                    md.append(" (").append(String.join(", ", f.getAffectedEndpoints())).append(")");
                }
                md.append("\n  ").append(f.getDescription()).append("\n");
            }
        }

        // JS file list
        var jsFiles = resourceStore.getJavaScriptFiles();
        if (!jsFiles.isEmpty()) {
            md.append("\n## JavaScript Files\n\n");
            for (String f : jsFiles) {
                md.append("- `captured/js/").append(f).append("`\n");
            }
        }

        Files.writeString(workDir.resolve("CLAUDE.md"), md.toString());
    }

    private String buildUserPrompt(String userPrompt, Path workDir) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Deep security analysis of captured HTTP traffic.\n\n");
        prompt.append("Working directory: ").append(workDir).append("\n");
        prompt.append("CLAUDE.md has the full traffic context.\n");
        prompt.append("All captured resources are in ./captured/\n\n");

        if (userPrompt != null && !userPrompt.isBlank()) {
            prompt.append("ADDITIONAL INSTRUCTIONS FROM THE ANALYST:\n");
            prompt.append(userPrompt).append("\n\n");
        }

        prompt.append("Follow the methodology in the system prompt. ");
        prompt.append("Read ALL files, analyze everything, generate all artifacts.\n");

        return prompt.toString();
    }

    // ========== System Prompt Loading ==========

    private String loadSystemPrompt() {
        // Load from PromptManager (supports filesystem overrides via Prompts tab)
        return promptManager.get("claude_code_agent");
    }

    // ========== Claude Binary Discovery ==========

    static String findClaudeBinary() {
        // Try common locations
        String[] candidates = {
                "claude",                                    // In PATH
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                System.getProperty("user.home") + "/.npm-global/bin/claude",
                System.getProperty("user.home") + "/.nvm/current/bin/claude",
        };

        for (String candidate : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(candidate, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }

        // Try `which claude`
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "claude");
            Process p = pb.start();
            if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String path = r.readLine();
                    if (path != null && !path.isBlank()) return path.trim();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    // ========== Stream JSON Parsing ==========

    /**
     * Parse a stream-json line from Claude Code.
     * Returns human-readable text, or null to skip.
     */
    private String parseStreamJson(String line) {
        if (line == null || line.isBlank()) return null;

        // Simple JSON parsing without Gson dependency
        // stream-json format: {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}
        try {
            if (line.contains("\"type\":\"assistant\"") && line.contains("\"text\"")) {
                // Extract text content
                int textIdx = line.indexOf("\"text\":\"");
                if (textIdx >= 0) {
                    int start = textIdx + 8;
                    int end = findClosingQuote(line, start);
                    if (end > start) {
                        return unescapeJson(line.substring(start, end));
                    }
                }
            }
            if (line.contains("\"type\":\"result\"")) {
                // Extract result info (cost, duration)
                if (line.contains("\"cost_usd\"")) {
                    int costIdx = line.indexOf("\"cost_usd\":");
                    if (costIdx >= 0) {
                        String costStr = extractJsonNumber(line, costIdx + 11);
                        return "[Result] Cost: $" + costStr;
                    }
                }
                return null;
            }
            if (line.contains("\"tool_use\"")) {
                // Show tool usage
                if (line.contains("\"name\":\"")) {
                    int nameIdx = line.indexOf("\"name\":\"");
                    int start = nameIdx + 8;
                    int end = line.indexOf("\"", start);
                    if (end > start) {
                        String toolName = line.substring(start, end);
                        return "[Tool] " + toolName;
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String extractJsonNumber(String s, int from) {
        int start = from;
        while (start < s.length() && (s.charAt(start) == ' ' || s.charAt(start) == ':')) start++;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        return start < end ? s.substring(start, end) : "?";
    }

    // ========== Artifact Collection ==========

    private List<GeneratedTool> collectArtifacts(Path workDir) {
        List<GeneratedTool> tools = new ArrayList<>();

        // Collect known artifact files
        collectFile(tools, workDir, "forja_suite.js", "PoC Suite",
                GeneratedTool.ToolType.JS_SCRIPT, "javascript");
        collectFile(tools, workDir, "forja_sniffer.js", "Application Sniffer",
                GeneratedTool.ToolType.JS_SCRIPT, "javascript");
        collectFile(tools, workDir, "forja_burp_crypto.py", "Burp Crypto Decryptor",
                GeneratedTool.ToolType.BURP_EXTENSION, "python");
        collectFile(tools, workDir, "forja_burp_auth.py", "Burp Auth Analyzer",
                GeneratedTool.ToolType.BURP_EXTENSION, "python");
        collectFile(tools, workDir, "forja_burp_recon.py", "Burp Active Recon",
                GeneratedTool.ToolType.BURP_EXTENSION, "python");

        // Also collect the report
        collectFile(tools, workDir, "forja_report.json", "Security Report",
                GeneratedTool.ToolType.JS_SCRIPT, "json"); // JS_SCRIPT as generic type

        return tools;
    }

    private void collectFile(List<GeneratedTool> tools, Path workDir, String filename,
                              String name, GeneratedTool.ToolType type, String language) {
        Path file = workDir.resolve(filename);
        if (Files.exists(file)) {
            try {
                String code = Files.readString(file);
                tools.add(new GeneratedTool(name, type,
                        "Generated by Claude Code deep analysis", code, language));
            } catch (IOException ignored) {}
        }
    }
}
