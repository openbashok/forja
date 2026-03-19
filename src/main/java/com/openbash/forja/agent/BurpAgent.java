package com.openbash.forja.agent;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.toolkit.GeneratedTool;
import com.openbash.forja.traffic.AppModel;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.audit.issues.AuditIssue;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

public class BurpAgent {

    private static final int MAX_HISTORY = 20;
    private static final int MAX_AGENT_ITERATIONS = 15;
    private static final int CONTEXT_TOKEN_BUDGET = 4000;

    private final MontoyaApi api;
    private final LLMProviderFactory providerFactory;
    private final ConfigManager config;
    private final PromptManager promptManager;
    private final AppModel appModel;
    private final Supplier<List<Finding>> findingsSupplier;
    private final ActionExecutor actionExecutor;
    private final ContextBuilder contextBuilder;
    private final LinkedList<Message> history = new LinkedList<>();
    private volatile AgentMode mode = AgentMode.ASK;

    public BurpAgent(MontoyaApi api, LLMProviderFactory providerFactory, ConfigManager config,
                     PromptManager promptManager, AppModel appModel, Supplier<List<Finding>> findingsSupplier,
                     ActionExecutor actionExecutor) {
        this.api = api;
        this.providerFactory = providerFactory;
        this.config = config;
        this.promptManager = promptManager;
        this.appModel = appModel;
        this.findingsSupplier = findingsSupplier;
        this.actionExecutor = actionExecutor;
        this.contextBuilder = new ContextBuilder(CONTEXT_TOKEN_BUDGET);
    }

    public AgentMode getMode() { return mode; }
    public void setMode(AgentMode mode) { this.mode = mode; }
    public int getMaxAgentIterations() { return MAX_AGENT_ITERATIONS; }

    public AgentResponse processUserMessage(String userMessage) throws LLMException {
        // Build system prompt with dynamic context
        String systemPrompt = buildSystemPrompt();

        // Add user message to history
        addToHistory(Message.user(userMessage));

        // Build message list: system + history
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(history);

        // Call LLM
        LLMProvider provider = providerFactory.create();
        LLMResponse llmResponse = provider.chat(messages, config.getModel(), config.getMaxGenerationTokens());

        // Parse response
        AgentResponse agentResponse = AgentResponse.parse(llmResponse.getContent());

        // Add assistant response to history
        addToHistory(Message.assistant(llmResponse.getContent()));

        return agentResponse;
    }

    public String executeAction(AgentAction action) {
        String result = actionExecutor.execute(action);
        // Inject result into history so the LLM sees outcomes in next turn
        String historyMsg = "[Action result: " + action.getTool() + "] " + result;

        // If a command/script failed with a dependency error, add a strong hint to auto-fix
        if (("run_command".equals(action.getTool())) && result.contains("Exit code: 1")) {
            String hint = detectDependencyError(result);
            if (hint != null) {
                historyMsg += "\n[System] " + hint + " Fix it now and re-run.";
            }
        }

        addToHistory(Message.user(historyMsg));
        return result;
    }

    private String detectDependencyError(String output) {
        if (output.contains("ModuleNotFoundError") || output.contains("ImportError")) {
            // Extract module name
            var matcher = java.util.regex.Pattern.compile("No module named '([^']+)'").matcher(output);
            if (matcher.find()) {
                return "Missing Python module '" + matcher.group(1) + "'. Install it with: run_command pip install " + matcher.group(1);
            }
            return "Missing Python dependency. Use run_command with pip install to fix it.";
        }
        if (output.contains("Cannot find module") || output.contains("MODULE_NOT_FOUND")) {
            var matcher = java.util.regex.Pattern.compile("Cannot find module '([^']+)'").matcher(output);
            if (matcher.find()) {
                return "Missing Node module '" + matcher.group(1) + "'. Install it with: run_command npm install " + matcher.group(1);
            }
            return "Missing Node.js dependency. Use run_command with npm install to fix it.";
        }
        if (output.contains("command not found") || output.contains("not recognized as")) {
            return "Command not found. Use run_command to install the missing tool.";
        }
        if (output.contains("No such file or directory") && output.contains("python")) {
            return "Python not found at expected path. Try python3 instead.";
        }
        return null;
    }

    public void clearHistory() {
        history.clear();
    }

    public List<GeneratedTool> getGeneratedTools() {
        return actionExecutor.getGeneratedTools();
    }

    public void updateGeneratedTool(int index, GeneratedTool updated) {
        actionExecutor.updateGeneratedTool(index, updated);
    }

    private String buildSystemPrompt() {
        String basePrompt = promptManager.get("agent_system");

        StringBuilder sb = new StringBuilder(basePrompt);

        // Append mode-specific instructions
        switch (mode) {
            case AGENT -> sb.append("\n\n").append(promptManager.get("agent_mode_agent"));
            case PLANNER -> sb.append("\n\n").append(promptManager.get("agent_mode_planner"));
            default -> {} // ASK mode uses base prompt only
        }

        // Append system environment info
        sb.append("\n\n## System Environment\n\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.arch")).append("\n");
        sb.append("Shell: ").append(System.getProperty("os.name", "").toLowerCase().contains("win") ? "cmd.exe / PowerShell" : "bash").append("\n");
        sb.append("Output directory: ").append(config.getOutputDir()).append("\n");

        sb.append("\n");
        sb.append(contextBuilder.buildContext(appModel));

        // Append Forja AI findings summary
        List<Finding> findings = findingsSupplier.get();
        if (!findings.isEmpty()) {
            sb.append("\n## Forja AI Findings\n\n");
            for (Finding f : findings) {
                sb.append("- [").append(f.getSeverity().getDisplayName()).append("] ").append(f.getTitle());
                if (!f.getAffectedEndpoints().isEmpty()) {
                    sb.append(" (").append(String.join(", ", f.getAffectedEndpoints())).append(")");
                }
                sb.append("\n");
            }
        }

        // Append Burp Scanner issues summary
        try {
            List<AuditIssue> burpIssues = api.siteMap().issues();
            if (!burpIssues.isEmpty()) {
                sb.append("\n## Burp Scanner Issues (").append(burpIssues.size()).append(")\n\n");
                int count = 0;
                for (AuditIssue issue : burpIssues) {
                    sb.append("- [").append(issue.severity().name()).append("] ")
                      .append(issue.name())
                      .append(" @ ").append(issue.baseUrl()).append("\n");
                    if (++count >= 30) {
                        sb.append("- ... and ").append(burpIssues.size() - 30).append(" more\n");
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // siteMap().issues() may not be available in all Burp editions
        }

        return sb.toString();
    }

    private void addToHistory(Message msg) {
        history.addLast(msg);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

}
