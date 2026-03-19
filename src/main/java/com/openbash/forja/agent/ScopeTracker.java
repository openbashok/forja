package com.openbash.forja.agent;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scope.ScopeChange;
import burp.api.montoya.scope.ScopeChangeHandler;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all known hosts and their scope status.
 * Combines: proxy history, site map, scope change events, and explicit additions.
 * Since the Montoya API has no listScope(), we build the picture from all available sources.
 */
public class ScopeTracker implements ScopeChangeHandler {

    private final MontoyaApi api;
    // All origins (scheme://host[:port]) we've ever seen
    private final Set<String> knownOrigins = ConcurrentHashMap.newKeySet();

    public ScopeTracker(MontoyaApi api) {
        this.api = api;
        api.scope().registerScopeChangeHandler(this);
    }

    @Override
    public void scopeChanged(ScopeChange scopeChange) {
        // Scope changed (add/remove from UI or API) — we don't know what changed,
        // but getInScopeHosts() will re-check all known origins on next call.
        // Pull fresh hosts from proxy history on each change to catch new targets.
        refreshFromProxyHistory();
    }

    /**
     * Explicitly track an origin (called when agent adds to scope, or any URL is encountered).
     */
    public void trackOrigin(String url) {
        String origin = extractOrigin(url);
        if (origin != null) knownOrigins.add(origin);
    }

    /**
     * Returns only the hosts that are currently IN scope.
     */
    public List<String> getInScopeHosts() {
        refreshFromAllSources();
        List<String> inScope = new ArrayList<>();
        for (String origin : knownOrigins) {
            try {
                if (api.scope().isInScope(origin)) {
                    inScope.add(origin);
                }
            } catch (Exception ignored) {}
        }
        // Deduplicate: if both http:// and https:// are in scope for same host,
        // keep both (they may be different scope entries)
        Collections.sort(inScope);
        return inScope;
    }

    /**
     * Returns hosts that are NOT in scope but have been seen in traffic.
     */
    public List<String> getOutOfScopeHosts() {
        refreshFromAllSources();
        List<String> outOfScope = new ArrayList<>();
        for (String origin : knownOrigins) {
            try {
                if (!api.scope().isInScope(origin)) {
                    outOfScope.add(origin);
                }
            } catch (Exception ignored) {}
        }
        Collections.sort(outOfScope);
        return outOfScope;
    }

    /**
     * Pull hosts from proxy history, site map, and any other source.
     */
    private void refreshFromAllSources() {
        refreshFromProxyHistory();
        refreshFromSiteMap();
    }

    private void refreshFromProxyHistory() {
        try {
            for (var entry : api.proxy().history()) {
                try {
                    String origin = extractOrigin(entry.request().url());
                    if (origin != null) knownOrigins.add(origin);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void refreshFromSiteMap() {
        try {
            for (HttpRequestResponse reqRes : api.siteMap().requestResponses()) {
                try {
                    String origin = extractOrigin(reqRes.request().url());
                    if (origin != null) knownOrigins.add(origin);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    static String extractOrigin(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            int port = uri.getPort();
            boolean defaultPort = (port == -1)
                    || ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return defaultPort
                    ? scheme + "://" + host
                    : scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }
}
