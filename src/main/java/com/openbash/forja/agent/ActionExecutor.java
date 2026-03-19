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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ActionExecutor {

    private final MontoyaApi api;
    private final AppModel appModel;
    private final Supplier<List<Finding>> findingsSupplier;
    private final Supplier<ToolkitGenerator> toolkitGeneratorSupplier;
    private final ScopeTracker scopeTracker;
    private final List<GeneratedTool> generatedTools = new CopyOnWriteArrayList<>();

    private static final int COMMAND_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

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
                case "run_command" -> runCommand(action.getParams());
                case "read_file" -> readFile(action.getParams());
                case "list_files" -> listFiles(action.getParams());
                case "write_file" -> writeFile(action.getParams());
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

    /**
     * Register a tool generated externally (e.g. from the Toolkit tab).
     */
    public void addGeneratedTool(GeneratedTool tool) {
        generatedTools.add(tool);
    }

    public void updateGeneratedTool(int index, GeneratedTool updated) {
        if (index >= 0 && index < generatedTools.size()) {
            generatedTools.set(index, updated);
        }
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

    // --- Shell & Filesystem Actions ---

    private String runCommand(JsonObject params) {
        String command = getStr(params, "command");
        if (command.isEmpty()) return "Error: 'command' parameter is required";

        String workDir = getStr(params, "working_dir");
        int timeout = COMMAND_TIMEOUT_SECONDS;
        if (params.has("timeout")) {
            try { timeout = params.get("timeout").getAsInt(); } catch (Exception ignored) {}
            timeout = Math.min(timeout, 300); // cap at 5 minutes
        }

        try {
            ProcessBuilder pb;
            if (IS_WINDOWS) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            // Set working directory
            if (!workDir.isEmpty()) {
                File dir = new File(workDir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                } else {
                    return "Error: working_dir '" + workDir + "' is not a valid directory";
                }
            }

            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append("\n");
                    }
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after " + timeout + "s. Partial output:\n" + output;
            }

            int exitCode = process.exitValue();
            String result = output.toString();
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n[... truncated at " + MAX_OUTPUT_CHARS + " chars ...]";
            }

            String header = "Exit code: " + exitCode + "\n";
            if (result.isEmpty()) {
                return header + "(no output)";
            }
            return header + result;

        } catch (Exception e) {
            return "Error running command: " + e.getMessage();
        }
    }

    private String readFile(JsonObject params) {
        String path = getStr(params, "path");
        if (path.isEmpty()) return "Error: 'path' parameter is required";

        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) return "Error: file not found: " + path;
            if (!Files.isRegularFile(filePath)) return "Error: not a regular file: " + path;

            long size = Files.size(filePath);
            if (size > 100_000) {
                return "Error: file too large (" + size + " bytes). Max 100KB.";
            }

            String content = Files.readString(filePath);
            if (content.length() > MAX_OUTPUT_CHARS) {
                content = content.substring(0, MAX_OUTPUT_CHARS)
                        + "\n[... truncated at " + MAX_OUTPUT_CHARS + " chars ...]";
            }
            return content;
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private String writeFile(JsonObject params) {
        String path = getStr(params, "path");
        String content = getStr(params, "content");
        if (path.isEmpty()) return "Error: 'path' parameter is required";
        if (content.isEmpty()) return "Error: 'content' parameter is required";

        try {
            Path filePath = Path.of(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            return "Written " + content.length() + " chars to " + path;
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    private String listFiles(JsonObject params) {
        String path = getStr(params, "path", ".");
        boolean recursive = params.has("recursive") && params.get("recursive").getAsBoolean();

        try {
            Path dir = Path.of(path);
            if (!Files.isDirectory(dir)) return "Error: not a directory: " + path;

            StringBuilder sb = new StringBuilder();
            sb.append("Contents of ").append(dir.toAbsolutePath()).append(":\n\n");

            var stream = recursive ? Files.walk(dir, 3) : Files.list(dir);
            List<Path> entries = stream.sorted().collect(Collectors.toList());
            stream.close();

            int count = 0;
            for (Path entry : entries) {
                if (entry.equals(dir)) continue;
                String type = Files.isDirectory(entry) ? "DIR " : "    ";
                String relative = dir.relativize(entry).toString();
                sb.append(type).append(relative);
                if (Files.isRegularFile(entry)) {
                    long size = Files.size(entry);
                    sb.append(" (").append(formatSize(size)).append(")");
                }
                sb.append("\n");
                if (++count >= 200) {
                    sb.append("... and more (truncated at 200 entries)\n");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error listing files: " + e.getMessage();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    // --- HTTP Actions ---

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
