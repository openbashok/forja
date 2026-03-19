package com.openbash.forja.traffic;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AppModel {

    private final ConcurrentHashMap<String, EndpointInfo> endpoints = new ConcurrentHashMap<>();
    private final Set<AuthInfo> authPatterns = ConcurrentHashMap.newKeySet();
    private final Set<String> cookies = ConcurrentHashMap.newKeySet();
    private final Set<String> techStack = ConcurrentHashMap.newKeySet();
    private final Set<String> interestingPatterns = ConcurrentHashMap.newKeySet();
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
    }

    private void evictLeastSeen() {
        endpoints.entrySet().stream()
                .min(Comparator.comparingInt(e -> e.getValue().getTimesSeen()))
                .ifPresent(e -> endpoints.remove(e.getKey()));
    }
}
