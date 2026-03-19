package com.openbash.forja.agent;

public enum AgentMode {
    ASK("Ask", "Single question-response. Good for quick queries."),
    AGENT("Agent", "Autonomous mode. Give a mission and the agent executes multiple steps."),
    PLANNER("Planner", "Creates a step-by-step attack plan without executing actions.");

    private final String displayName;
    private final String description;

    AgentMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    @Override
    public String toString() { return displayName; }
}
