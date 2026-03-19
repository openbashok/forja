package com.openbash.forja.agent;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.openbash.forja.analysis.Finding;
import com.openbash.forja.toolkit.GeneratedTool;
import com.openbash.forja.toolkit.ToolkitGenerator;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.EndpointInfo;

import burp.api.montoya.scanner.audit.issues.AuditIssue;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ActionExecutor {

    private final MontoyaApi api;
    private final AppModel appModel;
    private final Supplier<List<Finding>> findingsSupplier;
    private final Supplier<ToolkitGenerator> toolkitGeneratorSupplier;
    private final ScopeTracker scopeTracker;
    private final List<GeneratedTool> generatedTools = new CopyOnWriteArrayList<>();

    public ActionExecutor(MontoyaApi api, AppModel appModel,
                          Supplier<List<Finding>> findingsSupplier,
                          Supplier<ToolkitGenerator> toolkitGeneratorSupplier,
                          ScopeTracker scopeTracker) {
        this.api = api;
        this.appModel = appModel;
        this.findingsSupplier = findingsSupplier;
        this.toolkitGeneratorSupplier = toolkitGeneratorSupplier;
        this.scopeTracker = scopeTracker;
    }

    public String execute(AgentAction action) {
        try {
            return switch (action.getTool()) {
                case "add_to_scope" -> addToScope(action.getParams());
                case "remove_from_scope" -> removeFromScope(action.getParams());
                case "get_scope_status" -> getScopeStatus();
                case "check_scope" -> checkScope(action.getParams());
                case "list_endpoints" -> listEndpoints(action.getParams());
                case "get_endpoint_details" -> getEndpointDetails(action.getParams());
                case "get_traffic_stats" -> getTrafficStats();
                case "list_findings" -> listFindings(action.getParams());
                case "send_to_repeater" -> sendToRepeater(action.getParams());
                case "send_to_intruder" -> sendToIntruder(action.getParams());
                case "send_request" -> sendRequest(action.getParams());
                case "active_scan" -> activeScan(action.getParams());
                case "bulk_action" -> bulkAction(action.getParams());
                case "generate_tool" -> generateTool(action.getParams());
                case "save_file" -> saveFile(action.getParams());
                case "list_burp_issues" -> listBurpIssues(action.getParams());
                default -> "Unknown action: " + action.getTool();
            };
        } catch (Exception e) {
            return "Error executing " + action.getTool() + ": " + e.getMessage();
        }
    }

    private String addToScope(JsonObject params) {
        String url = getStr(params, "url");
        if (url.isEmpty()) return "Error: missing 'url' parameter";
        api.scope().includeInScope(url);
        scopeTracker.trackOrigin(url);
        return "Added " + url + " to scope";
    }

    private String removeFromScope(JsonObject params) {
        String url = getStr(params, "url");
        if (url.isEmpty()) return "Error: missing 'url' parameter";
        api.scope().excludeFromScope(url);
        return "Removed " + url + " from scope";
    }

    private String getScopeStatus() {
        StringBuilder sb = new StringBuilder();

        List<String> inScope = scopeTracker.getInScopeHosts();
        List<String> outOfScope = scopeTracker.getOutOfScopeHosts();

        if (inScope.isEmpty() && outOfScope.isEmpty()) {
            sb.append("No hosts found. The scope may be configured but no traffic has been captured yet.\n");
            sb.append("Try browsing the target through Burp's proxy, or use check_scope to verify a specific URL.\n");
            return sb.toString();
        }

        if (!inScope.isEmpty()) {
            sb.append("Hosts in scope (").append(inScope.size()).append("):\n");
            for (String host : inScope) {
                sb.append("  ").append(host).append("\n");
            }
        } else {
            sb.append("No hosts in scope.\n");
        }

        if (!outOfScope.isEmpty()) {
            sb.append("\nHosts seen but not in scope (").append(outOfScope.size()).append("):\n");
            for (String host : outOfScope) {
                sb.append("  ").append(host).append("\n");
            }
        }

        int total = inScope.size() + outOfScope.size();
        sb.append("\nTotal: ").append(inScope.size()).append("/").append(total).append(" hosts in scope\n");
        sb.append("Endpoints captured by Forja: ").append(appModel.getEndpointCount()).append("\n");
        return sb.toString();
    }

    private String checkScope(JsonObject params) {
        String url = getStr(params, "url");
        if (url.isEmpty()) return "Error: missing 'url' parameter";
        scopeTracker.trackOrigin(url);
        boolean inScope = api.scope().isInScope(url);
        return url + " is " + (inScope ? "IN SCOPE" : "NOT in scope");
    }

    private String listEndpoints(JsonObject params) {
        String filter = getStr(params, "filter").toLowerCase();
        Map<String, EndpointInfo> endpoints = appModel.getEndpoints();

        if (endpoints.isEmpty()) {
            return "No endpoints captured yet. Browse the target to capture traffic.";
        }

        List<EndpointInfo> filtered = endpoints.values().stream()
                .filter(e -> filter.isEmpty()
                        || e.getMethod().toLowerCase().contains(filter)
                        || e.getPathPattern().toLowerCase().contains(filter)
                        || e.getResponseCodes().stream().anyMatch(c -> String.valueOf(c).contains(filter)))
                .sorted((a, b) -> Integer.compare(b.getTimesSeen(), a.getTimesSeen()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(filtered.size()).append(" endpoint(s)");
        if (!filter.isEmpty()) sb.append(" matching '").append(filter).append("'");
        sb.append(":\n\n");

        for (EndpointInfo ep : filtered) {
            sb.append(ep.getMethod()).append(" ").append(ep.getPathPattern());
            sb.append(" (seen ").append(ep.getTimesSeen()).append("x");
            sb.append(", codes: ").append(ep.getResponseCodes());
            if (!ep.getQueryParams().isEmpty()) {
                sb.append(", params: ").append(ep.getQueryParams());
            }
            if (ep.getAuthInfo() != null) {
                sb.append(", auth: ").append(ep.getAuthInfo());
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private String getEndpointDetails(JsonObject params) {
        String method = getStr(params, "method").toUpperCase();
        String pathPattern = getStr(params, "path_pattern");
        if (method.isEmpty() || pathPattern.isEmpty()) {
            return "Error: 'method' and 'path_pattern' are required";
        }

        String key = method + " " + pathPattern;
        EndpointInfo ep = appModel.getEndpoints().get(key);
        if (ep == null) {
            return "Endpoint not found: " + key + ". Use list_endpoints to see available endpoints.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint: ").append(ep.getMethod()).append(" ").append(ep.getPathPattern()).append("\n");
        sb.append("Original path: ").append(ep.getPath()).append("\n");
        sb.append("Times seen: ").append(ep.getTimesSeen()).append("\n");
        sb.append("Response codes: ").append(ep.getResponseCodes()).append("\n");
        sb.append("Query params: ").append(ep.getQueryParams()).append("\n");
        sb.append("Request headers: ").append(ep.getRequestHeaders()).append("\n");
        if (ep.getAuthInfo() != null) sb.append("Auth: ").append(ep.getAuthInfo()).append("\n");
        if (ep.getContentType() != null) sb.append("Content-Type: ").append(ep.getContentType()).append("\n");
        if (ep.getSampleRequest() != null) sb.append("\nSample request:\n").append(ep.getSampleRequest()).append("\n");
        if (ep.getSampleResponse() != null) sb.append("\nSample response:\n").append(ep.getSampleResponse()).append("\n");
        return sb.toString();
    }

    private String getTrafficStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoints captured: ").append(appModel.getEndpointCount()).append("\n");
        if (!appModel.getTechStack().isEmpty()) {
            sb.append("Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        }
        if (!appModel.getAuthPatterns().isEmpty()) {
            sb.append("Auth patterns:\n");
            appModel.getAuthPatterns().forEach(a -> sb.append("  - ").append(a).append("\n"));
        }
        if (!appModel.getCookies().isEmpty()) {
            sb.append("Cookies: ").append(String.join(", ", appModel.getCookies())).append("\n");
        }
        if (!appModel.getInterestingPatterns().isEmpty()) {
            sb.append("Interesting patterns:\n");
            appModel.getInterestingPatterns().forEach(p -> sb.append("  - ").append(p).append("\n"));
        }
        return sb.toString();
    }

    private String listFindings(JsonObject params) {
        String severityFilter = getStr(params, "severity").toUpperCase();
        List<Finding> findings = findingsSupplier.get();

        if (findings.isEmpty()) {
            return "No findings yet. Run analysis first from the Analysis tab.";
        }

        List<Finding> filtered = findings.stream()
                .filter(f -> severityFilter.isEmpty()
                        || f.getSeverity().getDisplayName().equalsIgnoreCase(severityFilter))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(filtered.size()).append(" finding(s):\n\n");
        for (Finding f : filtered) {
            sb.append("[").append(f.getSeverity().getDisplayName()).append("] ").append(f.getTitle());
            if (!f.getAffectedEndpoints().isEmpty()) {
                sb.append(" — ").append(String.join(", ", f.getAffectedEndpoints()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String sendToRepeater(JsonObject params) {
        HttpRequest request = buildHttpRequest(params);
        if (request == null) return "Error: 'url' parameter is required";
        api.repeater().sendToRepeater(request);
        return "Sent " + getStr(params, "method", "GET") + " " + getStr(params, "url") + " to Repeater";
    }

    private String sendToIntruder(JsonObject params) {
        HttpRequest request = buildHttpRequest(params);
        if (request == null) return "Error: 'url' parameter is required";
        api.intruder().sendToIntruder(request);
        return "Sent " + getStr(params, "method", "GET") + " " + getStr(params, "url") + " to Intruder";
    }

    private String sendRequest(JsonObject params) {
        HttpRequest request = buildHttpRequest(params);
        if (request == null) return "Error: 'url' parameter is required";

        HttpRequestResponse reqRes = api.http().sendRequest(request);
        String body = reqRes.response() != null ? reqRes.response().toString() : "(no response)";
        if (body.length() > 2000) {
            body = body.substring(0, 2000) + "\n[... truncated at 2000 chars ...]";
        }
        return "Response:\n" + body;
    }

    private String activeScan(JsonObject params) {
        String url = getStr(params, "url");
        if (url.isEmpty()) return "Error: 'url' parameter is required";

        api.scanner().startAudit(
                burp.api.montoya.scanner.AuditConfiguration.auditConfiguration(
                        burp.api.montoya.scanner.BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS));
        return "Active audit started on " + url;
    }

    private String bulkAction(JsonObject params) {
        if (!params.has("actions") || !params.get("actions").isJsonArray()) {
            return "Error: 'actions' array is required";
        }
        JsonArray actionsArr = params.getAsJsonArray("actions");
        List<String> results = new ArrayList<>();

        for (JsonElement elem : actionsArr) {
            JsonObject actionObj = elem.getAsJsonObject();
            String tool = actionObj.has("tool") ? actionObj.get("tool").getAsString() : "";
            if ("bulk_action".equals(tool)) {
                results.add("Skipped nested bulk_action (not allowed)");
                continue;
            }
            JsonObject actionParams = actionObj.has("params") ? actionObj.getAsJsonObject("params") : new JsonObject();
            AgentAction subAction = new AgentAction(tool, actionParams);
            results.add(tool + " -> " + execute(subAction));
        }
        return String.join("\n", results);
    }

    public List<GeneratedTool> getGeneratedTools() {
        return Collections.unmodifiableList(generatedTools);
    }

    private String generateTool(JsonObject params) {
        String prompt = getStr(params, "prompt");
        if (prompt.isEmpty()) return "Error: 'prompt' parameter is required";

        try {
            ToolkitGenerator gen = toolkitGeneratorSupplier.get();
            var tool = gen.generateFromPrompt(appModel, findingsSupplier.get(), prompt);
            generatedTools.add(tool);
            return "Generated tool: " + tool.getName() + " (" + tool.getLanguage() + ", "
                    + tool.getCode().split("\n").length + " lines)\n"
                    + tool.getDescription();
        } catch (Exception e) {
            return "Error generating tool: " + e.getMessage();
        }
    }

    private String saveFile(JsonObject params) {
        String name = getStr(params, "name");
        String content = getStr(params, "content");
        String format = getStr(params, "format", "md");

        if (name.isEmpty()) return "Error: 'name' parameter is required";
        if (content.isEmpty()) return "Error: 'content' parameter is required";

        generatedTools.add(new GeneratedTool(
                name,
                GeneratedTool.ToolType.REPORT,
                "Report generated by Forja Agent",
                content,
                format
        ));
        return "File saved: " + name + "." + format + " (" + content.split("\n").length + " lines). Available in the Generated Files panel.";
    }

    private String listBurpIssues(JsonObject params) {
        String severityFilter = getStr(params, "severity").toUpperCase();
        String filter = getStr(params, "filter").toLowerCase();

        try {
            List<AuditIssue> issues = api.siteMap().issues();

            if (issues.isEmpty()) {
                return "No issues found in Burp's scanner. Run an active scan or browse the target to trigger passive checks.";
            }

            List<AuditIssue> filtered = issues.stream()
                    .filter(i -> severityFilter.isEmpty()
                            || i.severity().name().equalsIgnoreCase(severityFilter))
                    .filter(i -> filter.isEmpty()
                            || i.name().toLowerCase().contains(filter)
                            || (i.detail() != null && i.detail().toLowerCase().contains(filter))
                            || i.baseUrl().toLowerCase().contains(filter))
                    .collect(Collectors.toList());

            StringBuilder sb = new StringBuilder();
            sb.append("Burp Scanner Issues: ").append(filtered.size());
            if (!severityFilter.isEmpty()) sb.append(" (severity: ").append(severityFilter).append(")");
            if (!filter.isEmpty()) sb.append(" (filter: ").append(filter).append(")");
            sb.append(" of ").append(issues.size()).append(" total:\n\n");

            // Group by severity
            Map<String, List<AuditIssue>> bySeverity = filtered.stream()
                    .collect(Collectors.groupingBy(
                            i -> i.severity().name(),
                            LinkedHashMap::new,
                            Collectors.toList()));

            for (var entry : bySeverity.entrySet()) {
                sb.append("[").append(entry.getKey()).append("] (").append(entry.getValue().size()).append(")\n");
                for (AuditIssue issue : entry.getValue()) {
                    sb.append("  - ").append(issue.name());
                    sb.append(" @ ").append(issue.baseUrl());
                    if (issue.confidence() != null) {
                        sb.append(" (").append(issue.confidence().name().toLowerCase()).append(")");
                    }
                    sb.append("\n");
                    if (issue.detail() != null && !issue.detail().isEmpty()) {
                        // Strip HTML tags for readability, truncate
                        String detail = issue.detail().replaceAll("<[^>]+>", "").trim();
                        if (detail.length() > 200) detail = detail.substring(0, 200) + "...";
                        sb.append("    ").append(detail).append("\n");
                    }
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error accessing Burp scanner issues: " + e.getMessage();
        }
    }

    private HttpRequest buildHttpRequest(JsonObject params) {
        String url = getStr(params, "url");
        if (url.isEmpty()) return null;

        String method = getStr(params, "method", "GET");
        HttpRequest request = HttpRequest.httpRequestFromUrl(url).withMethod(method);

        if (params.has("headers") && params.get("headers").isJsonObject()) {
            JsonObject headers = params.getAsJsonObject("headers");
            for (String key : headers.keySet()) {
                request = request.withAddedHeader(key, headers.get(key).getAsString());
            }
        }

        String body = getStr(params, "body");
        if (!body.isEmpty()) {
            request = request.withBody(body);
        }

        return request;
    }

    private static String getStr(JsonObject obj, String key) {
        return getStr(obj, key, "");
    }

    private static String getStr(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }
}
