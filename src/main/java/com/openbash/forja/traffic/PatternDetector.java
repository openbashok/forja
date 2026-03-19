package com.openbash.forja.traffic;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PatternDetector {

    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern SEQUENTIAL_ID_PATTERN = Pattern.compile("/(\\d{1,10})(?:/|$|\\?)");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> TECH_HEADERS = Map.of(
            "X-Powered-By", "server-tech",
            "Server", "server",
            "X-AspNet-Version", "ASP.NET",
            "X-Runtime", "Ruby"
    );

    private static final Map<String, String> TECH_COOKIES = Map.of(
            "JSESSIONID", "Java",
            "PHPSESSID", "PHP",
            "ASP.NET_SessionId", "ASP.NET",
            "connect.sid", "Node.js/Express",
            "_rails_session", "Ruby on Rails",
            "laravel_session", "Laravel"
    );

    public List<String> detectPatterns(String headers, String body, String url) {
        List<String> patterns = new ArrayList<>();

        if (headers != null) {
            if (JWT_PATTERN.matcher(headers).find()) patterns.add("JWT token in headers");
            if (headers.contains("Access-Control-Allow-Origin")) patterns.add("CORS headers present");
            if (headers.contains("Access-Control-Allow-Origin: *")) patterns.add("CORS wildcard (*)");
        }

        if (body != null) {
            if (JWT_PATTERN.matcher(body).find()) patterns.add("JWT token in body");
        }

        if (url != null) {
            if (SEQUENTIAL_ID_PATTERN.matcher(url).find()) patterns.add("Sequential ID in URL");
            if (UUID_PATTERN.matcher(url).find()) patterns.add("UUID in URL");
        }

        return patterns;
    }

    public AuthInfo detectAuth(String headerName, String headerValue) {
        if (headerName == null || headerValue == null) return null;

        String lower = headerName.toLowerCase();
        if (lower.equals("authorization")) {
            if (headerValue.toLowerCase().startsWith("bearer ")) {
                String token = headerValue.substring(7).trim();
                String format = JWT_PATTERN.matcher(token).matches() ? "JWT" : "opaque";
                return new AuthInfo(AuthInfo.AuthType.BEARER, headerName, format, token);
            }
            return new AuthInfo(AuthInfo.AuthType.CUSTOM, headerName, "custom", headerValue);
        }
        if (lower.equals("x-api-key") || lower.equals("api-key") || lower.equals("apikey")) {
            return new AuthInfo(AuthInfo.AuthType.API_KEY, headerName, "api-key", headerValue);
        }
        return null;
    }

    public Set<String> detectTechStack(Map<String, String> responseHeaders, Set<String> cookieNames) {
        Set<String> tech = new HashSet<>();

        if (responseHeaders != null) {
            for (Map.Entry<String, String> entry : TECH_HEADERS.entrySet()) {
                String val = responseHeaders.get(entry.getKey());
                if (val != null) {
                    tech.add(entry.getValue() + ": " + val);
                }
            }
        }

        if (cookieNames != null) {
            for (Map.Entry<String, String> entry : TECH_COOKIES.entrySet()) {
                if (cookieNames.contains(entry.getKey())) {
                    tech.add(entry.getValue());
                }
            }
        }

        return tech;
    }

    public boolean hasJwt(String text) {
        return text != null && JWT_PATTERN.matcher(text).find();
    }

    public boolean hasSequentialId(String url) {
        return url != null && SEQUENTIAL_ID_PATTERN.matcher(url).find();
    }

    public List<String> extractReflectedParams(String url, String responseBody) {
        if (url == null || responseBody == null) return List.of();

        List<String> reflected = new ArrayList<>();
        int queryStart = url.indexOf('?');
        if (queryStart < 0) return reflected;

        String query = url.substring(queryStart + 1);
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && !kv[1].isEmpty() && kv[1].length() > 2) {
                if (responseBody.contains(kv[1])) {
                    reflected.add(kv[0]);
                }
            }
        }
        return reflected;
    }
}
