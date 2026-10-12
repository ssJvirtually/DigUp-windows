package com.digup.engine;

import com.digup.model.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * The on-disk SQLite index: files, segments (fp16 vectors), and FTS5 keyword table.
 */
public final class IndexStore implements Closeable {
    public static final long SCHEMA_VERSION = 5;
    public static final String MAIN_FILE = "index.sqlite";
    public static final String CODE_FILE = "code.sqlite";

    public static final String TOKENIZER = "porter unicode61 remove_diacritics 2";
    public static final String FTS_COLUMNS = "name, body, original UNINDEXED, tokenize = \"" + TOKENIZER + "\"";

    private final Path directory;
    private final Path file;
    private final Database db;
    private final Gson gson = new Gson();

    private List<String> scope = null;
    private String scopeSql = "";
    private final List<Object> scopeValues = new ArrayList<>();

    public IndexStore(Path directory, String fileName) throws Exception {
        this.directory = directory;
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }
        this.file = directory.resolve(fileName);
        this.db = new Database(file.toString());
        migrate();
    }

    public Path getFile() {
        return file;
    }

    public Database getDatabase() {
        return db;
    }

    public void setScope(List<String> roots) {
        this.scope = roots;
        scopeValues.clear();
        if (roots == null) {
            scopeSql = "";
        } else if (roots.isEmpty()) {
            scopeSql = " AND 0";
        } else {
            StringBuilder sb = new StringBuilder(" AND (");
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) sb.append(" OR ");
                sb.append("(path >= ? AND path < ?)");
                String prefix = roots.get(i).replace('\\', '/');
                if (!prefix.endsWith("/")) prefix += "/";
                scopeValues.add(prefix);
                scopeValues.add(prefix + "\uFFFF");
            }
            sb.append(")");
            scopeSql = sb.toString();
        }
    }

    private void migrate() throws Exception {
        long[] version = new long[]{0};
        db.query("PRAGMA user_version", rs -> version[0] = rs.getLong(1));
        if (version[0] >= SCHEMA_VERSION) return;

        db.transaction(() -> {
            try {
                if (version[0] == 0) {
                    createSchema();
                } else {
                    // incremental upgrades
                    if (version[0] < 5) {
                        try {
                            db.execute("UPDATE segments SET vec = NULL, modality = 'none' WHERE kind = 'ocr';");
                        } catch (Exception ignored) {}
                        db.execute("PRAGMA user_version = " + SCHEMA_VERSION + ";");
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Migration error", e);
            }
        });
    }

    private void createSchema() throws SQLException {
        db.execute("""
            CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS files(
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                size INTEGER NOT NULL,
                mtime REAL NOT NULL,
                inode INTEGER NOT NULL,
                device INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'pending',
                detail TEXT,
                info TEXT,
                indexed_at REAL
            );
            CREATE INDEX IF NOT EXISTS files_inode ON files(device, inode);
            CREATE INDEX IF NOT EXISTS files_state ON files(state, kind);
            CREATE TABLE IF NOT EXISTS segments(
                id INTEGER PRIMARY KEY,
                file INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                modality TEXT NOT NULL,
                loc REAL,
                loc_end REAL,
                excerpt TEXT,
                vec BLOB
            );
            CREATE INDEX IF NOT EXISTS segments_file ON segments(file);
            """);
        try {
            db.execute("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(" + FTS_COLUMNS + ");");
        } catch (SQLException e) {
            // fallback in case diacritics parameter unsupported
            db.execute("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(name, body, original UNINDEXED, tokenize = 'porter unicode61');");
        }
        db.execute("PRAGMA user_version = " + SCHEMA_VERSION + ";");
    }

    // MARK: Meta

    public String getMeta(String key) throws SQLException {
        String[] val = new String[]{null};
        db.query("SELECT value FROM meta WHERE key = ?", rs -> val[0] = rs.getString(1), key);
        return val[0];
    }

    public void setMeta(String key, String value) throws SQLException {
        if (value != null) {
            db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
        } else {
            db.run("DELETE FROM meta WHERE key = ?", key);
        }
    }

    // MARK: Files

    public static FileRecord record(ResultSet rs) throws SQLException {
        return new FileRecord(
            rs.getLong(1),
            rs.getString(2),
            FileKind.fromRaw(rs.getString(3)),
            rs.getLong(4),
            rs.getDouble(5),
            rs.getLong(6),
            rs.getLong(7),
            rs.getString(8)
        );
    }

    public List<FileRecord> filesUnder(List<String> roots) throws SQLException {
        List<FileRecord> out = new ArrayList<>();
        for (String root : roots) {
            String prefix = root.replace('\\', '/');
            if (!prefix.endsWith("/")) prefix += "/";
            db.query("SELECT id, path, kind, size, mtime, inode, device, state FROM files WHERE path >= ? AND path < ?",
                rs -> out.add(record(rs)), prefix, prefix + "\uFFFF");
        }
        return out;
    }

    public long insert(Candidate file) throws Exception {
        db.run("INSERT INTO files(path, kind, size, mtime, inode, device) VALUES(?, ?, ?, ?, ?, ?)",
            file.path, file.kind.getRaw(), file.size, file.mtime, file.inode, file.device);
        long id = db.getLastInsertId();
        addNameRow(id, file.path);
        return id;
    }

    public void requeue(long id, Candidate file) throws Exception {
        deleteSegments(id, true);
        db.run("""
            UPDATE files SET kind = ?, size = ?, mtime = ?, inode = ?, device = ?, state = 'pending',
                detail = NULL, info = NULL, indexed_at = NULL WHERE id = ?
            """, file.kind.getRaw(), file.size, file.mtime, file.inode, file.device, id);
    }

    public void requeue(long id) throws Exception {
        deleteSegments(id, true);
        db.run("UPDATE files SET state = 'pending', detail = NULL, info = NULL, indexed_at = NULL WHERE id = ?", id);
    }

    public void move(long id, String newPath) throws Exception {
        db.run("UPDATE files SET path = ? WHERE id = ?", newPath, id);
        db.run("""
            UPDATE fts SET name = ?, body = ? WHERE rowid IN (SELECT id FROM segments WHERE file = ? AND kind = 'name')
            """, SearchText.normalized(nameText(newPath)), SearchText.normalized(folderText(newPath)), id);
    }

    public void delete(long id) throws Exception {
        db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file = ?)", id);
        db.run("DELETE FROM files WHERE id = ?", id);
    }

    public int prune(List<String> keepingUnderRoots) throws Exception {
        List<String> prefixes = new ArrayList<>();
        for (String r : keepingUnderRoots) {
            String p = r.replace('\\', '/');
            prefixes.add(p.endsWith("/") ? p : p + "/");
        }
        List<Long> gone = new ArrayList<>();
        db.query("SELECT id, path FROM files", rs -> {
            String p = rs.getString(2).replace('\\', '/');
            boolean kept = false;
            for (String prefix : prefixes) {
                if (p.startsWith(prefix)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) gone.add(rs.getLong(1));
        });
        if (gone.isEmpty()) return 0;
        db.transaction(() -> {
            for (long id : gone) {
                try {
                    delete(id);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return gone.size();
    }

    public void resetAll() throws Exception {
        db.transaction(() -> {
            try {
                db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE kind != 'name')");
                db.run("DELETE FROM segments WHERE kind != 'name'");
                db.run("UPDATE files SET state = 'pending', detail = NULL, info = NULL, indexed_at = NULL");
                setMeta("fingerprint", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public List<FileRecord> pending(Integer limit, Set<Long> only) throws SQLException {
        List<FileRecord> out = new ArrayList<>();
        if (only != null && only.isEmpty()) return out;
        StringBuilder sql = new StringBuilder("SELECT id, path, kind, size, mtime, inode, device, state FROM files WHERE state = 'pending'");
        List<Object> params = new ArrayList<>(scopeValues);
        if (only != null) {
            sql.append(" AND id IN (");
            int i = 0;
            for (Long id : only) {
                if (i++ > 0) sql.append(",");
                sql.append(id);
            }
            sql.append(")");
        }
        sql.append(scopeSql);
        sql.append(" ORDER BY ").append(FileKind.indexingOrderSQL()).append(", mtime DESC LIMIT ?");
        params.add(limit != null ? limit : -1);

        db.query(sql.toString(), rs -> out.add(record(rs)), params.toArray());
        return out;
    }

    public int pendingCount() throws SQLException {
        int[] count = new int[]{0};
        db.query("SELECT COUNT(*) FROM files WHERE state = 'pending'" + scopeSql,
            rs -> count[0] = rs.getInt(1), scopeValues.toArray());
        return count[0];
    }

    public List<FileRecord> unfinished() throws SQLException {
        List<FileRecord> out = new ArrayList<>();
        db.query("SELECT id, path, kind, size, mtime, inode, device, state FROM files WHERE state = 'partial'" + scopeSql +
            " ORDER BY " + FileKind.indexingOrderSQL() + ", mtime DESC",
            rs -> out.add(record(rs)), scopeValues.toArray());
        return out;
    }

    public int[] unfinishedCount() throws SQLException {
        int[] res = new int[]{0, 0}; // [files, left]
        db.query("""
            SELECT COUNT(*), SUM(MAX(0, CAST(json_extract(info, '$.read_end') AS INTEGER)
                                      - CAST(json_extract(info, '$.read_to') AS INTEGER)))
            FROM files WHERE state = 'partial'
            """ + scopeSql, rs -> {
            res[0] = rs.getInt(1);
            res[1] = rs.getInt(2);
        }, scopeValues.toArray());
        return res;
    }

    public Map<String, String> info(long id) throws SQLException {
        Map<String, String> map = new HashMap<>();
        db.query("SELECT info FROM files WHERE id = ?", rs -> {
            String json = rs.getString(1);
            if (json != null && !json.isEmpty()) {
                Map<String, String> parsed = gson.fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
                if (parsed != null) map.putAll(parsed);
            }
        }, id);
        return map;
    }

    public Integer lastPlace(long id) throws SQLException {
        Integer[] place = new Integer[]{null};
        db.query("SELECT MAX(loc) FROM segments WHERE file = ? AND kind IN ('page', 'page_image', 'chunk')",
            rs -> {
                double val = rs.getDouble(1);
                if (!rs.wasNull()) place[0] = (int) val;
            }, id);
        return place[0];
    }

    public void finish(long id, String state, String detail, Map<String, String> info) throws SQLException {
        String json = (info == null || info.isEmpty()) ? null : gson.toJson(info);
        double now = System.currentTimeMillis() / 1000.0;
        db.run("UPDATE files SET state = ?, detail = ?, info = ?, indexed_at = ? WHERE id = ?",
            state, detail, json, now, id);
    }

    // MARK: Segments

    public void addSegment(long file, SegmentKind kind, Modality modality, Double loc, Double locEnd,
                           String excerpt, String text, float[] vector) throws Exception {
        byte[] blob = (vector != null) ? Vectors.half(vector) : null;
        String mod = (vector == null) ? Modality.none.getRaw() : modality.getRaw();
        db.run("INSERT INTO segments(file, kind, modality, loc, loc_end, excerpt, vec) VALUES(?, ?, ?, ?, ?, ?, ?)",
            file, kind.getRaw(), mod, loc, locEnd, excerpt, blob);

        if (text != null && !text.isEmpty()) {
            long segId = db.getLastInsertId();
            String norm = SearchText.normalized(text);
            String original = norm.equals(text) ? null : text;
            db.run("INSERT INTO fts(rowid, name, body, original) VALUES(?, '', ?, ?)",
                segId, norm, original);
        }
    }

    private void addNameRow(long file, String path) throws Exception {
        db.run("INSERT INTO segments(file, kind, modality) VALUES(?, 'name', 'none')", file);
        long segId = db.getLastInsertId();
        String name = SearchText.normalized(nameText(path));
        String folder = SearchText.normalized(folderText(path));
        db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)", segId, name, folder);
    }

    private void deleteSegments(long file, boolean keepingName) throws Exception {
        String filter = keepingName ? " AND kind != 'name'" : "";
        db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file = ?" + filter + ")", file);
        db.run("DELETE FROM segments WHERE file = ?" + filter, file);
    }

    public static String nameText(String path) {
        if (path == null) return "";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    public static String folderText(String path) {
        if (path == null) return "";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (idx <= 0) return "";
        String folder = path.substring(0, idx).replace('\\', '/');
        String userHome = System.getProperty("user.home").replace('\\', '/');
        if (folder.startsWith(userHome)) {
            folder = folder.substring(userHome.length());
        }
        return folder.replace('/', ' ').trim();
    }

    // MARK: Counts

    public static class Counts {
        public final Map<String, Map<String, Integer>> byKindAndState = new HashMap<>();
        public int vectors = 0;
        public int segments = 0;
        public int unfinished = 0;
        public int left = 0;
    }

    public Counts counts() throws SQLException {
        Counts c = new Counts();
        List<Object> params = new ArrayList<>(scopeValues);
        db.query("SELECT kind, state, COUNT(*) FROM files WHERE 1" + scopeSql + " GROUP BY kind, state",
            rs -> {
                String k = rs.getString(1);
                String s = rs.getString(2);
                int count = rs.getInt(3);
                c.byKindAndState.computeIfAbsent(k != null ? k : "?", x -> new HashMap<>()).put(s != null ? s : "?", count);
            }, params.toArray());

        int[] unf = unfinishedCount();
        c.unfinished = unf[0];
        c.left = unf[1];

        String inScope = (scope == null) ? "" : " AND file IN (SELECT id FROM files WHERE 1" + scopeSql + ")";
        db.query("SELECT COUNT(*), COUNT(vec) FROM segments WHERE kind != 'name'" + inScope,
            rs -> {
                c.segments = rs.getInt(1);
                c.vectors = rs.getInt(2);
            }, params.toArray());

        return c;
    }

    @Override
    public void close() {
        if (db != null) db.close();
    }
}
