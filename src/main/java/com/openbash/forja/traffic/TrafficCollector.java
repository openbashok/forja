package com.openbash.forja.traffic;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.*;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class TrafficCollector implements ProxyRequestHandler, ProxyResponseHandler {

    private static final Pattern STATIC_ASSET = Pattern.compile(
            "\\.(css|js|png|jpg|jpeg|gif|ico|svg|woff2?|ttf|eot|map)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("^\\d+$");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    private final MontoyaApi api;
    private final AppModel appModel;
    private final PatternDetector patternDetector;

    public TrafficCollector(MontoyaApi api, AppModel appModel) {
        this.api = api;
        this.appModel = appModel;
        this.patternDetector = new PatternDetector();
    }

    @Override
    public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest request) {
        return ProxyRequestReceivedAction.continueWith(request);
    }

    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest request) {
        return ProxyRequestToBeSentAction.continueWith(request);
    }

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        return ProxyResponseReceivedAction.continueWith(response);
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        try {
            processResponse(response);
        } catch (Exception e) {
            api.logging().logToError("Forja TrafficCollector error: " + e.getMessage());
        }
        return ProxyResponseToBeSentAction.continueWith(response);
    }

    private void processResponse(InterceptedResponse response) {
        var request = response.initiatingRequest();
        String url = request.url();

        if (!api.scope().isInScope(url)) return;
        if (isStaticAsset(url)) return;

        String method = request.method();
        String path = extractPath(url);
        String pathPattern = normalizePath(path);

        EndpointInfo endpoint = appModel.addOrUpdate(method, path, pathPattern);

        // Query params
        int queryStart = url.indexOf('?');
        if (queryStart >= 0) {
            String query = url.substring(queryStart + 1);
            for (String param : query.split("&")) {
                String key = param.split("=", 2)[0];
                if (!key.isEmpty()) endpoint.addQueryParam(key);
            }
        }

        // Request headers & auth detection
        for (var header : request.headers()) {
            String name = header.name();
            endpoint.addRequestHeader(name);

            AuthInfo auth = patternDetector.detectAuth(name, header.value());
            if (auth != null) {
                endpoint.setAuthInfo(auth);
                appModel.addAuthPattern(auth);
            }
        }

        // Response code
        endpoint.addResponseCode(response.statusCode());

        // Content type
        String ct = response.headerValue("Content-Type");
        if (ct != null) endpoint.setContentType(ct);

        // Cookies
        for (var header : response.headers()) {
            if ("Set-Cookie".equalsIgnoreCase(header.name())) {
                String cookieName = header.value().split("=", 2)[0].trim();
                appModel.addCookie(cookieName);
            }
        }

        // Tech stack from response headers
        Map<String, String> respHeaders = new HashMap<>();
        for (var header : response.headers()) {
            respHeaders.put(header.name(), header.value());
        }
        Set<String> tech = patternDetector.detectTechStack(respHeaders, appModel.getCookies());
        tech.forEach(appModel::addTechStack);

        // Sample request/response (keep first or update if auth present)
        if (endpoint.getSampleRequest() == null || endpoint.getAuthInfo() != null) {
            endpoint.setSampleRequest(truncate(request.toString(), 2000));
            endpoint.setSampleResponse(truncate(response.toString(), 2000));
        }

        // Pattern detection
        String headerStr = request.headers().toString();
        String bodyStr = response.bodyToString();
        List<String> patterns = patternDetector.detectPatterns(headerStr, bodyStr, url);
        patterns.forEach(appModel::addInterestingPattern);

        // Reflected params
        List<String> reflected = patternDetector.extractReflectedParams(url, bodyStr);
        if (!reflected.isEmpty()) {
            appModel.addInterestingPattern("Reflected params in " + pathPattern + ": " + String.join(", ", reflected));
        }
    }

    /**
     * Import existing proxy history into the AppModel.
     * Returns the number of entries imported.
     */
    public int importProxyHistory() {
        List<ProxyHttpRequestResponse> history = api.proxy().history();
        int imported = 0;

        for (ProxyHttpRequestResponse item : history) {
            try {
                HttpRequest request = item.request();
                HttpResponse response = item.response();
                if (request == null || response == null) continue;

                String url = request.url();
                if (!api.scope().isInScope(url)) continue;
                if (isStaticAsset(url)) continue;

                processHistoryItem(request, response, url);
                imported++;
            } catch (Exception e) {
                api.logging().logToError("Forja import error: " + e.getMessage());
            }
        }

        api.logging().logToOutput("Forja: Imported " + imported + " items from proxy history.");
        return imported;
    }

    private void processHistoryItem(HttpRequest request, HttpResponse response, String url) {
        String method = request.method();
        String path = extractPath(url);
        String pathPattern = normalizePath(path);

        EndpointInfo endpoint = appModel.addOrUpdate(method, path, pathPattern);

        // Query params
        int queryStart = url.indexOf('?');
        if (queryStart >= 0) {
            String query = url.substring(queryStart + 1);
            for (String param : query.split("&")) {
                String key = param.split("=", 2)[0];
                if (!key.isEmpty()) endpoint.addQueryParam(key);
            }
        }

        // Request headers & auth detection
        for (var header : request.headers()) {
            String name = header.name();
            endpoint.addRequestHeader(name);

            AuthInfo auth = patternDetector.detectAuth(name, header.value());
            if (auth != null) {
                endpoint.setAuthInfo(auth);
                appModel.addAuthPattern(auth);
            }
        }

        // Response code
        endpoint.addResponseCode(response.statusCode());

        // Content type
        String ct = response.headerValue("Content-Type");
        if (ct != null) endpoint.setContentType(ct);

        // Cookies
        for (var header : response.headers()) {
            if ("Set-Cookie".equalsIgnoreCase(header.name())) {
                String cookieName = header.value().split("=", 2)[0].trim();
                appModel.addCookie(cookieName);
            }
        }

        // Tech stack from response headers
        Map<String, String> respHeaders = new HashMap<>();
        for (var header : response.headers()) {
            respHeaders.put(header.name(), header.value());
        }
        Set<String> tech = patternDetector.detectTechStack(respHeaders, appModel.getCookies());
        tech.forEach(appModel::addTechStack);

        // Sample request/response
        if (endpoint.getSampleRequest() == null || endpoint.getAuthInfo() != null) {
            endpoint.setSampleRequest(truncate(request.toString(), 2000));
            endpoint.setSampleResponse(truncate(response.toString(), 2000));
        }

        // Pattern detection
        String headerStr = request.headers().toString();
        String bodyStr = response.bodyToString();
        List<String> patterns = patternDetector.detectPatterns(headerStr, bodyStr, url);
        patterns.forEach(appModel::addInterestingPattern);

        // Reflected params
        List<String> reflected = patternDetector.extractReflectedParams(url, bodyStr);
        if (!reflected.isEmpty()) {
            appModel.addInterestingPattern("Reflected params in " + pathPattern + ": " + String.join(", ", reflected));
        }
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] segments = path.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            normalized.append("/");
            if (NUMERIC_SEGMENT.matcher(seg).matches()) {
                normalized.append("{id}");
            } else if (UUID_SEGMENT.matcher(seg).matches()) {
                normalized.append("{uuid}");
            } else {
                normalized.append(seg);
            }
        }
        return normalized.isEmpty() ? "/" : normalized.toString();
    }

    private String extractPath(String url) {
        try {
            return new URL(url).getPath();
        } catch (Exception e) {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return url;
            int pathStart = url.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) return "/";
            int queryStart = url.indexOf('?', pathStart);
            return queryStart < 0 ? url.substring(pathStart) : url.substring(pathStart, queryStart);
        }
    }

    private boolean isStaticAsset(String url) {
        String path = extractPath(url);
        return STATIC_ASSET.matcher(path).find();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "\n[truncated]";
    }
}
