package com.ionsignal.minecraft.ionnerrus.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A utility class for wrapping text to a maximum line width.
 */
public final class ChatWrapper {

    private ChatWrapper() {
        // Private constructor to prevent instantiation
    }

    /**
     * Wraps a given string to a specified maximum line width.
     * This method performs word-aware wrapping and will break long words if necessary.
     *
     * @param text
     *            The text to wrap.
     * @param maxWidth
     *            The maximum number of characters per line.
     * @return A list of strings, where each string is a line of wrapped text.
     */
    public static List<String> wrap(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            // Handle words that are longer than the max width
            while (word.length() > maxWidth) {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }
                lines.add(word.substring(0, maxWidth));
                word = word.substring(maxWidth);
            }
            // Check if adding the next word fits on the current line
            if (currentLine.length() + word.length() + (currentLine.length() > 0 ? 1 : 0) > maxWidth) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder();
            }
            // Add a space if it's not the first word on the line
            if (currentLine.length() > 0) {
                currentLine.append(" ");
            }
            currentLine.append(word);
        }
        // Add the last remaining line
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
    }
}