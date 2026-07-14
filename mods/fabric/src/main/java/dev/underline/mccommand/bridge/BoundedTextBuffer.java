package dev.underline.mccommand.bridge;

import java.util.ArrayList;
import java.util.List;

final class BoundedTextBuffer {
    private final int maximumMessages;
    private final int maximumTotalCharacters;
    private final int maximumMessageCharacters;
    private final List<String> messages = new ArrayList<>();
    private int characters;
    private boolean truncated;

    BoundedTextBuffer(int maximumMessages, int maximumTotalCharacters, int maximumMessageCharacters) {
        this.maximumMessages = maximumMessages;
        this.maximumTotalCharacters = maximumTotalCharacters;
        this.maximumMessageCharacters = maximumMessageCharacters;
    }

    synchronized void add(String value) {
        if (truncated || messages.size() >= maximumMessages || characters >= maximumTotalCharacters) {
            truncated = true;
            return;
        }
        int maximum = Math.min(maximumMessageCharacters, maximumTotalCharacters - characters);
        String bounded = value;
        if (bounded.length() > maximum) {
            bounded = maximum <= 1 ? "…" : bounded.substring(0, maximum - 1) + "…";
            truncated = true;
        }
        messages.add(bounded);
        characters += bounded.length();
    }

    synchronized List<String> messages() {
        return List.copyOf(messages);
    }

    synchronized boolean truncated() {
        return truncated;
    }
}
