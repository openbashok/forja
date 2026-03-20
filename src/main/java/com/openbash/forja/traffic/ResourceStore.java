package com.openbash.forja.traffic;

import com.openbash.forja.config.ConfigManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists full captured traffic resources to disk — no truncation.
 * Directory layout:
 *   captured/
 *     js/           — JavaScript files (standalone + inline)
 *     html/         — HTML pages
 *     requests/     — Full HTTP request/response pairs
 *     crypto/       — Payloads with detected encryption/encoding patterns
 *
 * Generators read from these files to get complete context.
 */
public class ResourceStore {

    private final ConfigManager config;

    /** Track saved files: category → list of relative paths */
    private final ConcurrentHashMap<String, Set<String>> index = new ConcurrentHashMap<>();

    public ResourceStore(ConfigManager config) {
        this.config = config;
    }

    /** Base directory for captured resources. */
    public Path getCapturedDir() {
        return Path.of(config.getOutputDir(), "captured");
    }

    // ========== JavaScript ==========

    public void saveJavaScript(String url, String content) {
        if (content == null || content.isBlank()) return;
        String filename = sanitizeFilename(url, ".js");
        saveToDisk("js", filename, content);
    }

    /** Get all saved JS files with their full content. */
    public Map<String, String> getAllJavaScript() {
        return readAllFromDisk("js");
    }

    /** Get list of saved JS filenames. */
    public List<String> getJavaScriptFiles() {
        return listFiles("js");
    }

    // ========== HTML ==========

    public void saveHtml(String url, String content) {
        if (content == null || content.isBlank()) return;
        String filename = sanitizeFilename(url, ".html");
        saveToDisk("html", filename, content);
    }

    public Map<String, String> getAllHtml() {
        return readAllFromDisk("html");
    }

    // ========== Full HTTP Request/Response ==========

    /**
     * Save a complete request/response pair. No truncation.
     * Format: REQUEST\n---RESPONSE---\nRESPONSE
     */
    public void saveRequestResponse(String method, String pathPattern, String fullRequest, String fullResponse) {
        if (fullRequest == null && fullResponse == null) return;
        String filename = sanitizeFilename(method + "_" + pathPattern, ".txt");

        StringBuilder content = new StringBuilder();
        if (fullRequest != null) {
            content.append(fullRequest);
        }
        content.append("\n\n--- RESPONSE ---\n\n");
        if (fullResponse != null) {
            content.append(fullResponse);
        }

        saveToDisk("requests", filename, content.toString());
    }

    /** Get all saved request/response pairs. */
    public Map<String, String> getAllRequests() {
        return readAllFromDisk("requests");
    }

    // ========== Crypto Samples ==========

    public void saveCryptoSample(String label, String content) {
        if (content == null || content.isBlank()) return;
        String filename = sanitizeFilename(label, ".txt");
        saveToDisk("crypto", filename, content);
    }

    public Map<String, String> getAllCryptoSamples() {
        return readAllFromDisk("crypto");
    }

    // ========== Stats ==========

    public int getJsCount() { return countFiles("js"); }
    public int getHtmlCount() { return countFiles("html"); }
    public int getRequestCount() { return countFiles("requests"); }
    public int getCryptoCount() { return countFiles("crypto"); }

    // ========== Internal ==========

    private void saveToDisk(String category, String filename, String content) {
        try {
            Path dir = getCapturedDir().resolve(category);
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            Files.writeString(file, content);
            index.computeIfAbsent(category, k -> ConcurrentHashMap.newKeySet()).add(filename);
        } catch (IOException e) {
            // Silently fail — disk I/O shouldn't break traffic capture
        }
    }

    private Map<String, String> readAllFromDisk(String category) {
        Map<String, String> result = new LinkedHashMap<>();
        Path dir = getCapturedDir().resolve(category);
        if (!Files.isDirectory(dir)) return result;

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted() // Alphabetical for deterministic order
                    .forEach(file -> {
                        try {
                            result.put(file.getFileName().toString(), Files.readString(file));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}

        return result;
    }

    private List<String> listFiles(String category) {
        List<String> files = new ArrayList<>();
        Path dir = getCapturedDir().resolve(category);
        if (!Files.isDirectory(dir)) return files;

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(f -> files.add(f.getFileName().toString()));
        } catch (IOException ignored) {}

        return files;
    }

    private int countFiles(String category) {
        Path dir = getCapturedDir().resolve(category);
        if (!Files.isDirectory(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Convert a URL or label into a safe filename.
     * Preserves readability while removing unsafe characters.
     */
    static String sanitizeFilename(String input, String extension) {
        if (input == null || input.isBlank()) return "unknown" + extension;

        // Extract meaningful part from URL
        String name = input;

        // Remove protocol
        int schemeEnd = name.indexOf("://");
        if (schemeEnd >= 0) name = name.substring(schemeEnd + 3);

        // Remove query string for cleaner names
        int queryIdx = name.indexOf('?');
        if (queryIdx >= 0) name = name.substring(0, queryIdx);

        // Remove fragment
        int fragIdx = name.indexOf('#');
        if (fragIdx >= 0) {
            // Keep inline script markers: "page.html#inline-0" → "page.html_inline-0"
            name = name.substring(0, fragIdx) + "_" + name.substring(fragIdx + 1);
        }

        // Replace unsafe chars
        name = name.replaceAll("[^a-zA-Z0-9._/-]", "_");

        // Replace path separators with underscores
        name = name.replace('/', '_');

        // Collapse multiple underscores
        name = name.replaceAll("_+", "_");

        // Trim leading/trailing underscores
        name = name.replaceAll("^_|_$", "");

        // Cap length
        if (name.length() > 120) name = name.substring(0, 120);

        // Remove existing extension if it matches
        if (name.endsWith(extension)) name = name.substring(0, name.length() - extension.length());

        if (name.isBlank()) name = "resource";

        return name + extension;
    }
}
