package com.openbash.forja.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages system prompts with filesystem override support.
 * Load order: filesystem override (outputDir/prompts/) → bundled JAR resource.
 *
 * All prompt consumers should use PromptManager.get(key) instead of loading
 * resources directly. This enables runtime editing without recompilation.
 */
public class PromptManager {

    /** Registry of all known prompts: key → resource path. */
    private static final LinkedHashMap<String, PromptInfo> REGISTRY = new LinkedHashMap<>();

    static {
        register("global_rules",       "prompts/global_rules.txt",       "Global Rules",
                "Rules injected into ALL LLM calls. Proxy config, output format, safety constraints.");
        register("agent_system",       "prompts/agent_system.txt",       "Agent — Base System Prompt",
                "Core instructions for the AI agent: response format, available tools, rules.");
        register("agent_mode_agent",   "prompts/agent_mode_agent.txt",   "Agent — Autonomous Mode",
                "Instructions for autonomous multi-step execution mode.");
        register("agent_mode_planner", "prompts/agent_mode_planner.txt", "Agent — Planner Mode",
                "Instructions for attack planning mode (no execution).");
        register("analysis_system",    "prompts/analysis_system.txt",    "Security Analysis",
                "System prompt for the security analyzer that classifies findings.");
        register("js_generator",       "prompts/js_generator.txt",       "JS Script Generator",
                "Instructions for generating JavaScript security testing scripts.");
        register("js_sniffer",         "prompts/js_sniffer.txt",         "JS Object Sniffer",
                "Instructions for generating the runtime object/variable sniffer.");
        register("burp_generator",     "prompts/burp_generator.txt",     "Burp Extension Generator",
                "Instructions for generating Jython Burp extensions.");
        register("claude_code_agent", "prompts/forja_agent_prompt.md",  "Claude Code — Deep Analysis",
                "System prompt for Claude Code deep traffic analysis (claude -p).");
        registerQuickPrompts("agent_quick_prompts",  "prompts/agent_quick_prompts.txt",  "Agent — Quick Prompts",
                "Sidebar prompts for the Agent tab.");
        registerQuickPrompts("toolkit_quick_prompts","prompts/toolkit_quick_prompts.txt","Toolkit — Quick Prompts",
                "Dropdown prompts for the Toolkit tab.");
    }

    private final ConfigManager config;

    /** Cache of loaded prompts (cleared on save/reset). */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public PromptManager(ConfigManager config) {
        this.config = config;
    }

    /**
     * Get a prompt by key. Checks filesystem override first, then JAR resource.
     */
    public String get(String key) {
        return cache.computeIfAbsent(key, this::load);
    }

    /**
     * Get a system prompt with global rules appended.
     * Use this for all LLM system messages so global rules are always included.
     */
    public String getWithRules(String key) {
        String prompt = get(key);
        String rules = get("global_rules");
        if (rules == null || rules.isBlank()) return prompt;
        return prompt + "\n\n" + rules;
    }

    /**
     * Save an override for a prompt. Writes to filesystem and updates cache.
     */
    public void save(String key, String content) {
        PromptInfo info = REGISTRY.get(key);
        if (info == null) return;

        try {
            Path file = overridePath(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            cache.put(key, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save prompt override: " + e.getMessage(), e);
        }
    }

    /**
     * Reset a prompt to its bundled default. Deletes the filesystem override.
     */
    public void reset(String key) {
        PromptInfo info = REGISTRY.get(key);
        if (info == null) return;

        try {
            Path file = overridePath(key);
            Files.deleteIfExists(file);
        } catch (IOException ignored) {}

        cache.remove(key);
    }

    /**
     * Check if a prompt has a filesystem override.
     */
    public boolean hasOverride(String key) {
        return Files.exists(overridePath(key));
    }

    /**
     * Get the bundled default content for a prompt.
     */
    public String getDefault(String key) {
        PromptInfo info = REGISTRY.get(key);
        if (info == null) return "";
        return loadFromJar(info.resourcePath);
    }

    /**
     * Get all registered prompt keys and their info.
     */
    public Map<String, PromptInfo> getRegistry() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(REGISTRY));
    }

    /**
     * Clear the cache (forces reload on next get).
     */
    public void clearCache() {
        cache.clear();
    }

    // --- Internal ---

    private String load(String key) {
        PromptInfo info = REGISTRY.get(key);
        if (info == null) return "Unknown prompt: " + key;

        // 1. Try filesystem override
        Path overrideFile = overridePath(key);
        if (Files.exists(overrideFile)) {
            try {
                return Files.readString(overrideFile);
            } catch (IOException ignored) {}
        }

        // 2. Fallback to JAR resource
        return loadFromJar(info.resourcePath);
    }

    private Path overridePath(String key) {
        return Path.of(config.getOutputDir(), "prompts", key + ".txt");
    }

    private String loadFromJar(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return "# Prompt not found: " + resourcePath;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# Error loading prompt: " + e.getMessage();
        }
    }

    private static void register(String key, String resourcePath, String displayName, String description) {
        REGISTRY.put(key, new PromptInfo(key, resourcePath, displayName, description, false));
    }

    private static void registerQuickPrompts(String key, String resourcePath, String displayName, String description) {
        REGISTRY.put(key, new PromptInfo(key, resourcePath, displayName, description, true));
    }

    /**
     * Metadata about a registered prompt.
     */
    public static class PromptInfo {
        private final String key;
        private final String resourcePath;
        private final String displayName;
        private final String description;
        private final boolean quickPrompts;

        PromptInfo(String key, String resourcePath, String displayName, String description, boolean quickPrompts) {
            this.key = key;
            this.resourcePath = resourcePath;
            this.displayName = displayName;
            this.description = description;
            this.quickPrompts = quickPrompts;
        }

        public String getKey() { return key; }
        public String getResourcePath() { return resourcePath; }
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public boolean isQuickPrompts() { return quickPrompts; }
    }
}
