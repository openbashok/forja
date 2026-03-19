package com.openbash.forja.config;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Preferences;

public class ConfigManager {

    private static final String PREFIX = "forja.";

    private final Preferences prefs;

    public ConfigManager(MontoyaApi api) {
        this.prefs = api.persistence().preferences();
    }

    // Provider
    public String getProvider() {
        return getString("provider", "Anthropic");
    }

    public void setProvider(String provider) {
        setString("provider", provider);
    }

    // API Key
    public String getApiKey() {
        return getString("apiKey", "");
    }

    public void setApiKey(String key) {
        setString("apiKey", key);
    }

    // Model
    public String getModel() {
        return getString("model", "claude-sonnet-4-20250514");
    }

    public void setModel(String model) {
        setString("model", model);
    }

    // Custom Endpoint
    public String getCustomEndpoint() {
        return getString("customEndpoint", "");
    }

    public void setCustomEndpoint(String endpoint) {
        setString("customEndpoint", endpoint);
    }

    // Budget (max USD per session)
    public double getBudget() {
        String val = getString("budget", "1.0");
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    public void setBudget(double budget) {
        setString("budget", String.valueOf(budget));
    }

    // Max generation tokens (LLM output limit for generated tools)
    public int getMaxGenerationTokens() {
        String val = getString("maxGenerationTokens", "16384");
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 16384;
        }
    }

    public void setMaxGenerationTokens(int max) {
        setString("maxGenerationTokens", String.valueOf(max));
    }

    // Max traffic entries
    public int getMaxTrafficEntries() {
        String val = getString("maxTrafficEntries", "500");
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 500;
        }
    }

    public void setMaxTrafficEntries(int max) {
        setString("maxTrafficEntries", String.valueOf(max));
    }

    // Output directory for generated files
    public String getOutputDir() {
        return getString("outputDir", System.getProperty("java.io.tmpdir") + "/forja-output");
    }

    public void setOutputDir(String dir) {
        setString("outputDir", dir);
    }

    private String getString(String key, String defaultValue) {
        String val = prefs.getString(PREFIX + key);
        return val != null ? val : defaultValue;
    }

    private void setString(String key, String value) {
        prefs.setString(PREFIX + key, value);
    }
}
