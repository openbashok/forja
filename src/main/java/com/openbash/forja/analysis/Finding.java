package com.openbash.forja.analysis;

import java.util.List;

public class Finding {

    private final String title;
    private final Severity severity;
    private final String description;
    private final String evidence;
    private final List<String> affectedEndpoints;
    private final String recommendation;
    private final List<String> cwes;

    public Finding(String title, Severity severity, String description, String evidence,
                   List<String> affectedEndpoints, String recommendation, List<String> cwes) {
        this.title = title;
        this.severity = severity;
        this.description = description;
        this.evidence = evidence;
        this.affectedEndpoints = affectedEndpoints;
        this.recommendation = recommendation;
        this.cwes = cwes;
    }

    public String getTitle() { return title; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public String getEvidence() { return evidence; }
    public List<String> getAffectedEndpoints() { return affectedEndpoints; }
    public String getRecommendation() { return recommendation; }
    public List<String> getCwes() { return cwes; }
}
