package com.openbash.forja.traffic;

public class AuthInfo {

    public enum AuthType {
        BEARER, COOKIE, API_KEY, CUSTOM, NONE
    }

    private final AuthType type;
    private final String headerName;
    private final String tokenFormat;
    private final String tokenSample;

    public AuthInfo(AuthType type, String headerName, String tokenFormat, String tokenSample) {
        this.type = type;
        this.headerName = headerName;
        this.tokenFormat = tokenFormat;
        this.tokenSample = redact(tokenSample);
    }

    public AuthType getType() { return type; }
    public String getHeaderName() { return headerName; }
    public String getTokenFormat() { return tokenFormat; }
    public String getTokenSample() { return tokenSample; }

    private static String redact(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    @Override
    public String toString() {
        return type + " via " + headerName + " (" + tokenFormat + ")";
    }
}
