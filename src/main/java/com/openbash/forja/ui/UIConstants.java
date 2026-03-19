package com.openbash.forja.ui;

import javax.swing.*;
import java.awt.*;

public final class UIConstants {

    private UIConstants() {}

    public static final int PAD = 10;
    public static final int SMALL_PAD = 5;

    public static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    public static final Font BOLD_FONT = new Font(Font.DIALOG, Font.BOLD, 12);

    public static Color background() {
        Color c = UIManager.getColor("Panel.background");
        return c != null ? c : Color.DARK_GRAY;
    }

    public static Color foreground() {
        Color c = UIManager.getColor("Panel.foreground");
        return c != null ? c : Color.LIGHT_GRAY;
    }

    public static Color textBackground() {
        Color c = UIManager.getColor("TextArea.background");
        return c != null ? c : Color.BLACK;
    }

    public static Color textForeground() {
        Color c = UIManager.getColor("TextArea.foreground");
        return c != null ? c : Color.WHITE;
    }

    public static Color severityCritical() { return new Color(220, 50, 50); }
    public static Color severityHigh() { return new Color(255, 100, 50); }
    public static Color severityMedium() { return new Color(255, 180, 0); }
    public static Color severityLow() { return new Color(100, 180, 255); }
    public static Color severityInfo() { return new Color(150, 150, 150); }
}
