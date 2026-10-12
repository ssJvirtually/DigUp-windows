package com.digup.model;

/**
 * One searchable piece of a file.
 */
public enum SegmentKind {
    name("name"),
    image("image"),
    ocr("ocr"),
    page("page"),
    page_image("page_image"),
    chunk("chunk"),
    audio("audio"),
    frame("frame"),
    lines("lines"),
    cells("cells"),
    full("full"),
    moment("moment");

    private final String raw;

    SegmentKind(String raw) {
        this.raw = raw;
    }

    public String getRaw() { return raw; }
    public String getId() { return raw; }

    public static SegmentKind fromRaw(String raw) {
        if (raw == null) return name;
        for (SegmentKind s : values()) {
            if (s.raw.equalsIgnoreCase(raw)) return s;
        }
        return name;
    }

    public static SegmentKind fromId(String id) {
        return fromRaw(id);
    }
}
