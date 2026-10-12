package com.digup.model;

/**
 * A file as recorded in the SQLite database index.
 */
public final class FileRecord {
    private final long id;
    private final String path;
    private final FileKind kind;
    private final long size;
    private final double mtime;
    private final long inode;
    private final long device;
    private final String state;

    public FileRecord(long id, String path, FileKind kind, long size, double mtime, long inode, long device, String state) {
        this.id = id;
        this.path = path;
        this.kind = kind;
        this.size = size;
        this.mtime = mtime;
        this.inode = inode;
        this.device = device;
        this.state = state;
    }

    public long getId() { return id; }
    public String getPath() { return path; }
    public FileKind getKind() { return kind; }
    public long getSize() { return size; }
    public double getMtime() { return mtime; }
    public long getInode() { return inode; }
    public long getDevice() { return device; }
    public String getState() { return state; }
}
