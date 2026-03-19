package com.openbash.forja.analysis;

import com.openbash.forja.ui.UIConstants;

import java.awt.Color;

public enum Severity {
    CRITICAL("Critical", UIConstants.severityCritical()),
    HIGH("High", UIConstants.severityHigh()),
    MEDIUM("Medium", UIConstants.severityMedium()),
    LOW("Low", UIConstants.severityLow()),
    INFO("Info", UIConstants.severityInfo());

    private final String displayName;
    private final Color color;

    Severity(String displayName, Color color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() { return displayName; }
    public Color getColor() { return color; }

    public static Severity fromString(String s) {
        if (s == null) return INFO;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }
}
