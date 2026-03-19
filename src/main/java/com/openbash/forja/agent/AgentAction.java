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

    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public String toString() {
        return tool + " " + params;
    }
}
