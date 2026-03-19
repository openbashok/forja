package com.openbash.forja.llm;

public class LLMException extends Exception {

    private final int statusCode;

    public LLMException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LLMException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() { return statusCode; }

    public boolean isRateLimit() {
        return statusCode == 429 || statusCode == 529;
    }

    public boolean isAuthError() {
        return statusCode == 401 || statusCode == 403;
    }
}
