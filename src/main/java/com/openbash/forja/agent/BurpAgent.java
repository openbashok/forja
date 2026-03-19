package com.openbash.forja.agent;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

public class BurpAgent {

    private static final int MAX_HISTORY = 20;
    private static final int CONTEXT_TOKEN_BUDGET = 4000;

    private final LLMProviderFactory providerFactory;
    private final ConfigManager config;
    private final AppModel appModel;
    private final Supplier<List<Finding>> findingsSupplier;
    private final ActionExecutor actionExecutor;
    private final ContextBuilder contextBuilder;
    private final LinkedList<Message> history = new LinkedList<>();

    public BurpAgent(LLMProviderFactory providerFactory, ConfigManager config,
                     AppModel appModel, Supplier<List<Finding>> findingsSupplier,
                     ActionExecutor actionExecutor) {
        this.providerFactory = providerFactory;
        this.config = config;
        this.appModel = appModel;
        this.findingsSupplier = findingsSupplier;
        this.actionExecutor = actionExecutor;
        this.contextBuilder = new ContextBuilder(CONTEXT_TOKEN_BUDGET);
    }

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

    private String buildSystemPrompt() {
        String basePrompt = loadPrompt("prompts/agent_system.txt");

        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n");
        sb.append(contextBuilder.buildContext(appModel));

        // Append findings summary
        List<Finding> findings = findingsSupplier.get();
        if (!findings.isEmpty()) {
            sb.append("\n## Security Findings\n\n");
            for (Finding f : findings) {
                sb.append("- [").append(f.getSeverity().getDisplayName()).append("] ").append(f.getTitle());
                if (!f.getAffectedEndpoints().isEmpty()) {
                    sb.append(" (").append(String.join(", ", f.getAffectedEndpoints())).append(")");
                }
                sb.append("\n");
            }
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
