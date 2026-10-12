package com.digup.model;

/**
 * File kinds corresponding to DigUp classification.
 */
public enum FileKind {
    screenshot("screenshot", "Screenshot", "Screenshots", 0),
    image("image", "Picture", "Pictures", 1),
    pdf("pdf", "PDF", "PDFs", 2),
    doc("doc", "Document", "Documents", 3),
    audio("audio", "Recording", "Audio", 4),
    video("video", "Video", "Videos", 5),
    code("code", "Code", "Code", 6);

    private final String raw;
    private final String displayName;
    private final String pluralName;
    private final int order;

    FileKind(String raw, String displayName, String pluralName, int order) {
        this.raw = raw;
        this.displayName = displayName;
        this.pluralName = pluralName;
        this.order = order;
    }

    public String getRaw() { return raw; }
    public String getId() { return raw; }
    public String getDisplayName() { return displayName; }
    public String getPluralName() { return pluralName; }
    public int getOrder() { return order; }

    public static FileKind fromRaw(String raw) {
        if (raw == null) return doc;
        for (FileKind k : values()) {
            if (k.raw.equalsIgnoreCase(raw)) return k;
        }
        return doc;
    }

    public static FileKind fromId(String id) {
        return fromRaw(id);
    }

    public static String indexingOrderSQL() {
        return "CASE kind WHEN 'screenshot' THEN 0 WHEN 'image' THEN 1 WHEN 'pdf' THEN 2 WHEN 'doc' THEN 3 " +
               "WHEN 'audio' THEN 4 WHEN 'video' THEN 5 ELSE 6 END";
    }
}
