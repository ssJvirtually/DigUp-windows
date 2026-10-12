package com.digup.model;

import java.util.Map;

/**
 * A ranked hit returned by hybrid semantic + keyword search.
 */
public class SearchHit {
    private final String path;
    private final FileKind kind;
    private final double score;
    private final Double z;
    private final Double cosine;
    private final Integer keywordRank;
    private final SegmentKind segment;
    private final long segmentID;
    private final Double loc;
    private final Double locEnd;
    private final String excerpt;
    private final double modified;
    private final long size;
    private final Map<String, String> info;
    private Double focus;

    public SearchHit(String path, FileKind kind, double score, Double z, Double cosine,
                     Integer keywordRank, SegmentKind segment, long segmentID, Double loc,
                     Double locEnd, String excerpt, double modified, long size,
                     Map<String, String> info, Double focus) {
        this.path = path;
        this.kind = kind;
        this.score = score;
        this.z = z;
        this.cosine = cosine;
        this.keywordRank = keywordRank;
        this.segment = segment;
        this.segmentID = segmentID;
        this.loc = loc;
        this.locEnd = locEnd;
        this.excerpt = excerpt;
        this.modified = modified;
        this.size = size;
        this.info = info;
        this.focus = focus;
    }

    public String getPath() { return path; }
    public FileKind getKind() { return kind; }
    public double getScore() { return score; }
    public Double getZ() { return z; }
    public Double getCosine() { return cosine; }
    public Integer getKeywordRank() { return keywordRank; }
    public SegmentKind getSegment() { return segment; }
    public long getSegmentID() { return segmentID; }
    public Double getLoc() { return loc; }
    public Double getLocEnd() { return locEnd; }
    public String getExcerpt() { return excerpt; }
    public double getModified() { return modified; }
    public long getSize() { return size; }
    public Map<String, String> getInfo() { return info; }
    public Double getFocus() { return focus; }
    public void setFocus(Double focus) { this.focus = focus; }

    public String getFileName() {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
