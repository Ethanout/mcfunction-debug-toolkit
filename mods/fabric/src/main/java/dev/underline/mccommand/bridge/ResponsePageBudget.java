package dev.underline.mccommand.bridge;

import com.google.gson.JsonElement;

import java.nio.charset.StandardCharsets;

public final class ResponsePageBudget {
    public static final int DEFAULT_LIMIT = 64;
    public static final int MAX_LIMIT = 256;
    public static final int MAX_JSON_BYTES = 2 * 1_024 * 1_024;

    private final int maximumItems;
    private final int maximumBytes;
    private int items;
    private int bytes;

    public ResponsePageBudget(int maximumItems, int maximumBytes) {
        this.maximumItems = maximumItems;
        this.maximumBytes = maximumBytes;
    }

    public boolean tryAdd(JsonElement value) {
        int encoded = value.toString().getBytes(StandardCharsets.UTF_8).length;
        if (items >= maximumItems || bytes + encoded > maximumBytes) return false;
        items++;
        bytes += encoded;
        return true;
    }

    public int items() {
        return items;
    }

    public int bytes() {
        return bytes;
    }
}
