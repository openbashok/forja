package com.openbash.forja.agent;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.toolkit.GeneratedTool;
import com.openbash.forja.traffic.AppModel;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scanner.audit.issues.AuditIssue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final AppModel appModel;
    private final Supplier<List<Finding>> findingsSupplier;
    private final ActionExecutor actionExecutor;
    private final ContextBuilder contextBuilder;
    private final LinkedList<Message> history = new LinkedList<>();
    private volatile AgentMode mode = AgentMode.ASK;

    public BurpAgent(MontoyaApi api, LLMProviderFactory providerFactory, ConfigManager config,
                     AppModel appModel, Supplier<List<Finding>> findingsSupplier,
                     ActionExecutor actionExecutor) {
        this.api = api;
        this.providerFactory = providerFactory;
        this.config = config;
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
        addToHistory(Message.user("[Action result: " + action.getTool() + "] " + result));
        return result;
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
        String basePrompt = loadPrompt("prompts/agent_system.txt");

        StringBuilder sb = new StringBuilder(basePrompt);

        // Append mode-specific instructions
        switch (mode) {
            case AGENT -> {
                String modePrompt = loadPrompt("prompts/agent_mode_agent.txt");
                sb.append("\n\n").append(modePrompt);
            }
            case PLANNER -> {
                String modePrompt = loadPrompt("prompts/agent_mode_planner.txt");
                sb.append("\n\n").append(modePrompt);
            }
            default -> {} // ASK mode uses base prompt only
        }

        sb.append("\n\n");
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

    private String loadPrompt(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return "You are a Burp Suite assistant. Respond with JSON: {\"response\": \"...\", \"actions\": []}";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "You are a Burp Suite assistant. Respond with JSON: {\"response\": \"...\", \"actions\": []}";
        }
    }
}
