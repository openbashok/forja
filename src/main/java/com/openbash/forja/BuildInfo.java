package com.openbash.forja;

import java.io.InputStream;
import java.util.Properties;

/**
 * Reads version info generated at build time from version.properties.
 */
public final class BuildInfo {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = BuildInfo.class.getClassLoader()
                .getResourceAsStream("version/version.properties")) {
            if (is != null) PROPS.load(is);
        } catch (Exception ignored) {}
    }

    public static String getVersion() {
        return PROPS.getProperty("version", "dev");
    }

    public static String getCommit() {
        return PROPS.getProperty("commit", "unknown");
    }

    public static String getCommitFull() {
        return PROPS.getProperty("commit.full", "unknown");
    }

    public static String getBuildTime() {
        return PROPS.getProperty("build.time", "");
    }

    /** e.g. "1.0.0 (d2a7924)" */
    public static String getVersionString() {
        return getVersion() + " (" + getCommit() + ")";
    }

    private BuildInfo() {}
}
