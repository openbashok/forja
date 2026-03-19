package com.openbash.forja.traffic;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class EndpointInfo {

    private final String method;
    private final String path;
    private final String pathPattern;
    private final AtomicInteger timesSeen = new AtomicInteger(1);
    private final Set<String> queryParams = ConcurrentHashMap.newKeySet();
    private final Set<String> requestHeaders = ConcurrentHashMap.newKeySet();
    private final Set<Integer> responseCodes = ConcurrentHashMap.newKeySet();
    private volatile AuthInfo authInfo;
    private volatile String sampleRequest;
    private volatile String sampleResponse;
    private volatile String contentType;

    public EndpointInfo(String method, String path, String pathPattern) {
        this.method = method;
        this.path = path;
        this.pathPattern = pathPattern;
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getPathPattern() { return pathPattern; }
    public int getTimesSeen() { return timesSeen.get(); }
    public Set<String> getQueryParams() { return Collections.unmodifiableSet(queryParams); }
    public Set<String> getRequestHeaders() { return Collections.unmodifiableSet(requestHeaders); }
    public Set<Integer> getResponseCodes() { return Collections.unmodifiableSet(responseCodes); }
    public AuthInfo getAuthInfo() { return authInfo; }
    public String getSampleRequest() { return sampleRequest; }
    public String getSampleResponse() { return sampleResponse; }
    public String getContentType() { return contentType; }

    public void incrementSeen() { timesSeen.incrementAndGet(); }
    public void addQueryParam(String param) { queryParams.add(param); }
    public void addRequestHeader(String header) { requestHeaders.add(header); }
    public void addResponseCode(int code) { responseCodes.add(code); }
    public void setAuthInfo(AuthInfo authInfo) { this.authInfo = authInfo; }
    public void setSampleRequest(String req) { this.sampleRequest = req; }
    public void setSampleResponse(String res) { this.sampleResponse = res; }
    public void setContentType(String ct) { this.contentType = ct; }

    public String getKey() {
        return method + " " + pathPattern;
    }
}
