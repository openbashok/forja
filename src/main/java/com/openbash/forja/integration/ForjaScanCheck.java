package com.openbash.forja.integration;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scanner.audit.insertionpoint.AuditInsertionPoint;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity;
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence;
import burp.api.montoya.scanner.AuditResult;
import burp.api.montoya.scanner.ConsolidationAction;
import burp.api.montoya.scanner.ScanCheck;
import com.openbash.forja.traffic.PatternDetector;

import java.util.ArrayList;
import java.util.List;

import static burp.api.montoya.scanner.AuditResult.auditResult;
import static burp.api.montoya.scanner.ConsolidationAction.KEEP_EXISTING;
import static burp.api.montoya.scanner.ConsolidationAction.KEEP_NEW;

public class ForjaScanCheck implements ScanCheck {

    private final PatternDetector detector = new PatternDetector();

    @Override
    public AuditResult activeAudit(HttpRequestResponse baseRequestResponse, AuditInsertionPoint insertionPoint) {
        return auditResult(List.of());
    }

    @Override
    public AuditResult passiveAudit(HttpRequestResponse requestResponse) {
        List<AuditIssue> issues = new ArrayList<>();

        String url = requestResponse.request().url();
        String responseStr = requestResponse.response() != null ? requestResponse.response().toString() : "";
        String responseBody = requestResponse.response() != null ? requestResponse.response().bodyToString() : "";

        // Check for JWT in response body
        if (detector.hasJwt(responseBody)) {
            issues.add(AuditIssue.auditIssue(
                    "JWT Token in Response Body",
                    "Forja detected a JWT token in the response body. Ensure tokens are transmitted securely.",
                    null,
                    url,
                    AuditIssueSeverity.INFORMATION,
                    AuditIssueConfidence.CERTAIN,
                    null,
                    null,
                    AuditIssueSeverity.INFORMATION,
                    requestResponse
            ));
        }

        // Check for sequential IDs
        if (detector.hasSequentialId(url)) {
            issues.add(AuditIssue.auditIssue(
                    "Sequential ID Detected",
                    "Forja detected a sequential numeric ID in the URL path. This may indicate IDOR vulnerability potential.",
                    null,
                    url,
                    AuditIssueSeverity.INFORMATION,
                    AuditIssueConfidence.TENTATIVE,
                    null,
                    null,
                    AuditIssueSeverity.INFORMATION,
                    requestResponse
            ));
        }

        // Check for CORS wildcard
        if (responseStr.contains("Access-Control-Allow-Origin: *")) {
            issues.add(AuditIssue.auditIssue(
                    "CORS Wildcard Origin",
                    "The response includes Access-Control-Allow-Origin: *, which may allow unauthorized cross-origin access.",
                    null,
                    url,
                    AuditIssueSeverity.LOW,
                    AuditIssueConfidence.CERTAIN,
                    null,
                    null,
                    AuditIssueSeverity.LOW,
                    requestResponse
            ));
        }

        // Check for reflected parameters
        List<String> reflected = detector.extractReflectedParams(url, responseBody);
        if (!reflected.isEmpty()) {
            issues.add(AuditIssue.auditIssue(
                    "Reflected Parameters Detected",
                    "Parameters reflected in response: " + String.join(", ", reflected)
                            + ". This may indicate XSS vulnerability potential.",
                    null,
                    url,
                    AuditIssueSeverity.MEDIUM,
                    AuditIssueConfidence.TENTATIVE,
                    null,
                    null,
                    AuditIssueSeverity.MEDIUM,
                    requestResponse
            ));
        }

        return auditResult(issues);
    }

    @Override
    public ConsolidationAction consolidateIssues(AuditIssue newIssue, AuditIssue existingIssue) {
        if (newIssue.name().equals(existingIssue.name())) {
            return KEEP_EXISTING;
        }
        return KEEP_NEW;
    }
}
