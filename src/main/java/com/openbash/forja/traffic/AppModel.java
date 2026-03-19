package com.openbash.forja.traffic;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AppModel {

    private final ConcurrentHashMap<String, EndpointInfo> endpoints = new ConcurrentHashMap<>();
    private final Set<AuthInfo> authPatterns = ConcurrentHashMap.newKeySet();
    private final Set<String> cookies = ConcurrentHashMap.newKeySet();
    private final Set<String> techStack = ConcurrentHashMap.newKeySet();
    private final Set<String> interestingPatterns = ConcurrentHashMap.newKeySet();

    /** JavaScript source code captured from proxy traffic: URL → source content. */
    private final ConcurrentHashMap<String, String> jsSources = new ConcurrentHashMap<>();
    private static final int MAX_JS_SOURCES = 30;
    private static final int MAX_JS_SIZE = 50_000; // 50KB per file

    private volatile int maxEntries = 500;

    public void setMaxEntries(int max) { this.maxEntries = max; }

    public EndpointInfo addOrUpdate(String method, String path, String pathPattern) {
        String key = method + " " + pathPattern;
        EndpointInfo existing = endpoints.get(key);
        if (existing != null) {
            existing.incrementSeen();
            return existing;
        }

        if (endpoints.size() >= maxEntries) {
            evictLeastSeen();
        }

        EndpointInfo info = new EndpointInfo(method, path, pathPattern);
        EndpointInfo prev = endpoints.putIfAbsent(key, info);
        if (prev != null) {
            prev.incrementSeen();
            return prev;
        }
        return info;
    }

    public void addAuthPattern(AuthInfo auth) { authPatterns.add(auth); }
    public void addCookie(String cookie) { cookies.add(cookie); }
    public void addTechStack(String tech) { techStack.add(tech); }
    public void addInterestingPattern(String pattern) { interestingPatterns.add(pattern); }

    /**
     * Store captured JavaScript source code from proxy traffic.
     * Skips minified mega-files (single line > 5000 chars) and vendor/library code.
     */
    public void addJsSource(String url, String content) {
        if (content == null || content.isBlank()) return;
        if (jsSources.size() >= MAX_JS_SOURCES && !jsSources.containsKey(url)) return;

        // Skip vendor/library code
        String urlLower = url.toLowerCase();
        if (urlLower.contains("/vendor/") || urlLower.contains("/node_modules/")
                || urlLower.contains("jquery") || urlLower.contains("react.")
                || urlLower.contains("angular.") || urlLower.contains("vue.")
                || urlLower.contains("bootstrap") || urlLower.contains("polyfill")
                || urlLower.contains("cdn.") || urlLower.contains("cdnjs.")
                || urlLower.contains("unpkg.com") || urlLower.contains("jsdelivr")) {
            return;
        }

        // Truncate if too large
        String source = content.length() > MAX_JS_SIZE
                ? content.substring(0, MAX_JS_SIZE) + "\n// [truncated by Forja]"
                : content;

        jsSources.put(url, source);
    }

    public Map<String, String> getJsSources() { return Collections.unmodifiableMap(jsSources); }

    public Map<String, EndpointInfo> getEndpoints() { return Collections.unmodifiableMap(endpoints); }
    public Set<AuthInfo> getAuthPatterns() { return Collections.unmodifiableSet(authPatterns); }
    public Set<String> getCookies() { return Collections.unmodifiableSet(cookies); }
    public Set<String> getTechStack() { return Collections.unmodifiableSet(techStack); }
    public Set<String> getInterestingPatterns() { return Collections.unmodifiableSet(interestingPatterns); }

    public int getEndpointCount() { return endpoints.size(); }

    public void clear() {
        endpoints.clear();
        authPatterns.clear();
        cookies.clear();
        techStack.clear();
        interestingPatterns.clear();
        jsSources.clear();
    }

    private void evictLeastSeen() {
        endpoints.entrySet().stream()
                .min(Comparator.comparingInt(e -> e.getValue().getTimesSeen()))
                .ifPresent(e -> endpoints.remove(e.getKey()));
    }
}
