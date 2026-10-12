package com.digup.model;

/**
 * A file found by the crawler ready to be considered for indexing.
 */
public final class Candidate {
    public final String path;
    public final FileKind kind;
    public final long size;
    public final double mtime;
    public final long inode;
    public final long device;

    public Candidate(String path, FileKind kind, long size, double mtime, long inode, long device) {
        this.path = path;
        this.kind = kind;
        this.size = size;
        this.mtime = mtime;
        this.inode = inode;
        this.device = device;
    }

    public String path() { return path; }
    public FileKind kind() { return kind; }
    public long size() { return size; }
    public double mtime() { return mtime; }
    public long inode() { return inode; }
    public long device() { return device; }

    public String getPath() { return path; }
    public FileKind getKind() { return kind; }
    public long getSize() { return size; }
    public double getMtime() { return mtime; }
    public long getInode() { return inode; }
    public long getDevice() { return device; }
}
