package com.openbash.forja.llm;

import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.CryptoDetector;
import com.openbash.forja.traffic.EndpointInfo;
import com.openbash.forja.util.TokenEstimator;

import java.util.*;
import java.util.stream.Collectors;

public class ContextBuilder {

    private final int maxTokenBudget;

    public ContextBuilder(int maxTokenBudget) {
        this.maxTokenBudget = maxTokenBudget;
    }

    public String buildContext(AppModel appModel) {
        StringBuilder sb = new StringBuilder();

        // App overview
        sb.append("## Application Overview\n\n");
        sb.append("Endpoints discovered: ").append(appModel.getEndpointCount()).append("\n");
        if (!appModel.getTechStack().isEmpty()) {
            sb.append("Tech stack: ").append(String.join(", ", appModel.getTechStack())).append("\n");
        }
        if (!appModel.getAuthPatterns().isEmpty()) {
            sb.append("Auth patterns: ").append(
                    appModel.getAuthPatterns().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining("; "))
            ).append("\n");
        }
        if (!appModel.getCookies().isEmpty()) {
            sb.append("Cookies: ").append(String.join(", ", appModel.getCookies())).append("\n");
        }
        if (!appModel.getInterestingPatterns().isEmpty()) {
            sb.append("\n## Interesting Patterns\n");
            appModel.getInterestingPatterns().forEach(p -> sb.append("- ").append(p).append("\n"));
        }

        // Crypto findings
        var cryptoFindings = appModel.getCryptoFindings();
        if (!cryptoFindings.isEmpty()) {
            sb.append("\n## Detected Cryptographic Patterns\n\n");
            for (CryptoDetector.CryptoFinding cf : cryptoFindings) {
                sb.append("- [").append(cf.getType()).append("] ").append(cf.getDescription()).append("\n");
                sb.append("  URL: ").append(cf.getUrl()).append("\n");
                sb.append("  Sample: ").append(cf.getSample()).append("\n");
            }
        }

        // JavaScript sources summary
        if (!appModel.getJsSources().isEmpty()) {
            sb.append("\nJavaScript files captured: ").append(appModel.getJsSources().size()).append("\n");
        }

        // ALL endpoints — prioritized by auth > params > frequency, no truncation
        sb.append("\n## Endpoints\n\n");
        List<EndpointInfo> endpoints = new ArrayList<>(appModel.getEndpoints().values());
        endpoints.sort(Comparator
                .<EndpointInfo, Integer>comparing(e -> e.getAuthInfo() != null ? 0 : 1)
                .thenComparing(e -> -e.getQueryParams().size())
                .thenComparing(e -> -e.getTimesSeen()));

        for (EndpointInfo ep : endpoints) {
            sb.append("### ").append(ep.getMethod()).append(" ").append(ep.getPathPattern()).append("\n");
            sb.append("Seen: ").append(ep.getTimesSeen()).append(" | ");
            sb.append("Codes: ").append(ep.getResponseCodes()).append(" | ");
            sb.append("Params: ").append(ep.getQueryParams()).append("\n");
            if (ep.getAuthInfo() != null) {
                sb.append("Auth: ").append(ep.getAuthInfo()).append("\n");
            }
            if (ep.getContentType() != null) {
                sb.append("Content-Type: ").append(ep.getContentType()).append("\n");
            }
            if (ep.getSampleRequest() != null) {
                sb.append("```\n").append(ep.getSampleRequest()).append("\n```\n");
            }
            if (ep.getSampleResponse() != null) {
                sb.append("Response:\n```\n").append(ep.getSampleResponse()).append("\n```\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public int estimateContextTokens(AppModel appModel) {
        return TokenEstimator.estimateTokens(buildContext(appModel));
    }
}
