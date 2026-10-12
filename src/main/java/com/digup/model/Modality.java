package com.digup.model;

/**
 * Modality of an embedding vector.
 */
public enum Modality {
    none("none"),
    text("text"),
    image("image"),
    audio("audio");

    private final String raw;

    Modality(String raw) {
        this.raw = raw;
    }

    public String getRaw() { return raw; }
    public String getId() { return raw; }

    public static Modality fromRaw(String raw) {
        if (raw == null) return none;
        for (Modality m : values()) {
            if (m.raw.equalsIgnoreCase(raw)) return m;
        }
        return none;
    }

    public static Modality fromId(String id) {
        return fromRaw(id);
    }
}
