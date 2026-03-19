package com.openbash.forja.toolkit;

import java.time.Instant;

public class GeneratedTool {

    public enum ToolType {
        JS_SCRIPT("JavaScript Script"),
        BURP_EXTENSION("Burp Extension"),
        REPORT("Report");

        private final String displayName;
        ToolType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private final String name;
    private final ToolType type;
    private final String description;
    private final String code;
    private final String language;
    private final Instant generatedAt;

    public GeneratedTool(String name, ToolType type, String description, String code, String language) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.code = code;
        this.language = language;
        this.generatedAt = Instant.now();
    }

    public String getName() { return name; }
    public ToolType getType() { return type; }
    public String getDescription() { return description; }
    public String getCode() { return code; }
    public String getLanguage() { return language; }
    public Instant getGeneratedAt() { return generatedAt; }
}
