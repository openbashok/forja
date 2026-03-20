package com.openbash.forja.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A quick-action prompt entry used in Agent sidebar and Toolkit dropdown.
 * Parsed from text files with format:
 *
 *   # Category Name
 *   Label | Full prompt text
 *   Another Label | Another prompt
 *
 * Lines starting with # are category headers.
 * Lines with | separate label from prompt.
 * Lines without | use the whole line as both label and prompt.
 * Empty lines and lines starting with // are ignored.
 */
public record QuickPrompt(String category, String label, String prompt) {

    /**
     * A category header (label and prompt are null).
     */
    public boolean isCategory() {
        return label == null;
    }

    /**
     * Parse the text format into a list of QuickPrompt entries.
     */
    public static List<QuickPrompt> parse(String text) {
        List<QuickPrompt> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        String currentCategory = null;

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            if (line.startsWith("#")) {
                currentCategory = line.substring(1).trim();
                result.add(new QuickPrompt(currentCategory, null, null));
            } else {
                int pipe = line.indexOf('|');
                if (pipe > 0) {
                    String label = line.substring(0, pipe).trim();
                    String prompt = line.substring(pipe + 1).trim();
                    result.add(new QuickPrompt(currentCategory, label, prompt));
                } else {
                    result.add(new QuickPrompt(currentCategory, line, line));
                }
            }
        }
        return result;
    }
}
