package com.openbash.forja.agent;

import com.google.gson.JsonObject;

import java.util.Set;

public class AgentAction {

    private final String tool;
    private final JsonObject params;

    public AgentAction(String tool, JsonObject params) {
        this.tool = tool;
        this.params = params != null ? params : new JsonObject();
    }

    public String getTool() { return tool; }
    public JsonObject getParams() { return params; }

    private static final Set<String> CONFIRM_TOOLS = Set.of(
            "active_scan", "send_request", "run_command", "write_file"
    );

    public boolean requiresConfirmation() {
        return CONFIRM_TOOLS.contains(tool);
    }

    @Override
    public String toString() {
        return tool + " " + params;
    }
}
