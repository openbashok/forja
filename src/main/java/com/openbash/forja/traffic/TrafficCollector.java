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
    private static final Pattern JS_FILE = Pattern.compile("\\.js(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "<script[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
    private static final int MIN_INLINE_JS_LENGTH = 100; // Skip trivial inline scripts
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("^\\d+$");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    private final MontoyaApi api;
    private final AppModel appModel;
    private final PatternDetector patternDetector;
    private final CryptoDetector cryptoDetector;
    private volatile ResourceStore resourceStore;

    public TrafficCollector(MontoyaApi api, AppModel appModel) {
        this.api = api;
        this.appModel = appModel;
        this.patternDetector = new PatternDetector();
        this.cryptoDetector = new CryptoDetector();
    }

    /** Set ResourceStore for persisting full resources to disk. */
    public void setResourceStore(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
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

        // Capture JavaScript source code before filtering static assets
        captureJavaScript(url, response);

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

        // Full request/response — no truncation
        String fullRequest = request.toString();
        String fullResponse = response.toString();
        if (endpoint.getSampleRequest() == null || endpoint.getAuthInfo() != null) {
            endpoint.setSampleRequest(fullRequest);
            endpoint.setSampleResponse(fullResponse);
        }

        // Save full request/response to disk
        ResourceStore store = this.resourceStore;
        if (store != null) {
            store.saveRequestResponse(method, pathPattern, fullRequest, fullResponse);
        }

        // Save HTML to disk
        if (ct != null && ct.toLowerCase().contains("html")) {
            String htmlBody = response.bodyToString();
            if (store != null && htmlBody != null && !htmlBody.isBlank()) {
                store.saveHtml(url, fullResponse);
            }
        }

        // Pattern detection
        String headerStr = request.headers().toString();
        String bodyStr = response.bodyToString();
        List<String> patterns = patternDetector.detectPatterns(headerStr, bodyStr, url);
        patterns.forEach(appModel::addInterestingPattern);

        // Crypto detection
        String reqBody = request.bodyToString();
        List<CryptoDetector.CryptoFinding> cryptoFindings = cryptoDetector.analyze(
                url, headerStr, reqBody, response.headers().toString(), bodyStr);
        for (CryptoDetector.CryptoFinding cf : cryptoFindings) {
            appModel.addCryptoFinding(cf);
            appModel.addInterestingPattern("Crypto: " + cf.getDescription());
            if (store != null) {
                store.saveCryptoSample(cf.getDescription(), cf.getSample());
            }
        }

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

                // Capture JavaScript before filtering static assets
                captureJavaScriptFromHistory(url, response);

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

        // Full request/response — no truncation
        String fullRequest = request.toString();
        String fullResponse = response.toString();
        if (endpoint.getSampleRequest() == null || endpoint.getAuthInfo() != null) {
            endpoint.setSampleRequest(fullRequest);
            endpoint.setSampleResponse(fullResponse);
        }

        // Save full request/response to disk
        ResourceStore store = this.resourceStore;
        if (store != null) {
            store.saveRequestResponse(method, pathPattern, fullRequest, fullResponse);
        }

        // Save HTML to disk
        if (ct != null && ct.toLowerCase().contains("html")) {
            if (store != null) {
                store.saveHtml(url, fullResponse);
            }
        }

        // Pattern detection
        String headerStr = request.headers().toString();
        String bodyStr = response.bodyToString();
        List<String> patterns = patternDetector.detectPatterns(headerStr, bodyStr, url);
        patterns.forEach(appModel::addInterestingPattern);

        // Crypto detection
        String reqBody = request.bodyToString();
        List<CryptoDetector.CryptoFinding> cryptoFindings = cryptoDetector.analyze(
                url, headerStr, reqBody, response.headers().toString(), bodyStr);
        for (CryptoDetector.CryptoFinding cf : cryptoFindings) {
            appModel.addCryptoFinding(cf);
            appModel.addInterestingPattern("Crypto: " + cf.getDescription());
            if (store != null) {
                store.saveCryptoSample(cf.getDescription(), cf.getSample());
            }
        }

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

    /**
     * Capture JavaScript from proxy responses:
     * - Standalone .js files (Content-Type: javascript)
     * - Inline &lt;script&gt; blocks from HTML responses
     */
    private void captureJavaScript(InterceptedResponse response) {
        try {
            String url = response.initiatingRequest().url();
            captureJavaScript(url, response);
        } catch (Exception ignored) {}
    }

    private void captureJavaScript(String url, InterceptedResponse response) {
        try {
            String ct = response.headerValue("Content-Type");
            if (ct == null) return;
            String ctLower = ct.toLowerCase();

            String body = response.bodyToString();
            if (body == null || body.isBlank()) return;

            if (ctLower.contains("javascript")) {
                appModel.addJsSource(url, body);
                ResourceStore store = this.resourceStore;
                if (store != null) store.saveJavaScript(url, body);
                // Crypto analysis on JS source
                List<CryptoDetector.CryptoFinding> jsCrypto = cryptoDetector.analyzeJavaScript(url, body);
                for (CryptoDetector.CryptoFinding cf : jsCrypto) {
                    appModel.addCryptoFinding(cf);
                    appModel.addInterestingPattern("Crypto: " + cf.getDescription());
                    if (store != null) store.saveCryptoSample(cf.getDescription(), cf.getSample());
                }
            } else if (ctLower.contains("html")) {
                extractInlineScripts(url, body);
            }
        } catch (Exception ignored) {}
    }

    private void captureJavaScriptFromHistory(String url, HttpResponse response) {
        try {
            String ct = response.headerValue("Content-Type");
            if (ct == null) return;
            String ctLower = ct.toLowerCase();

            String body = response.bodyToString();
            if (body == null || body.isBlank()) return;

            if (ctLower.contains("javascript")) {
                appModel.addJsSource(url, body);
                ResourceStore store = this.resourceStore;
                if (store != null) store.saveJavaScript(url, body);
                List<CryptoDetector.CryptoFinding> jsCrypto = cryptoDetector.analyzeJavaScript(url, body);
                for (CryptoDetector.CryptoFinding cf : jsCrypto) {
                    appModel.addCryptoFinding(cf);
                    appModel.addInterestingPattern("Crypto: " + cf.getDescription());
                    if (store != null) store.saveCryptoSample(cf.getDescription(), cf.getSample());
                }
            } else if (ctLower.contains("html")) {
                extractInlineScripts(url, body);
            }
        } catch (Exception ignored) {}
    }

    private void extractInlineScripts(String pageUrl, String html) {
        var matcher = SCRIPT_TAG.matcher(html);
        int index = 0;
        while (matcher.find()) {
            String script = matcher.group(1).trim();
            if (script.length() >= MIN_INLINE_JS_LENGTH) {
                String tag = matcher.group(0);
                if (tag.contains(" src=") && script.isEmpty()) continue;

                String scriptUrl = pageUrl + "#inline-" + index;
                appModel.addJsSource(scriptUrl, script);
                ResourceStore store = this.resourceStore;
                if (store != null) store.saveJavaScript(scriptUrl, script);
                index++;
            }
        }
    }
}
