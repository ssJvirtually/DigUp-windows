import path from 'path';
import fs from 'fs';
import os from 'os';
import { Database } from './Database.js';
import { FileRecord, FileKind, Candidate, SegmentKind, Modality, IndexOptions } from '../shared/types.js';
import { SearchText } from './SearchText.js';
import { Vectors } from './Vectors.js';

export interface Counts {
  byKindAndState: Record<string, Record<string, number>>;
  vectors: number;
  segments: number;
  unfinished: number;
  left: number;
}

export class IndexStore {
  public readonly directory: string;
  public readonly file: string;
  public readonly db: Database;
  public static readonly schemaVersion = 5;

  public static readonly mainFile = 'index.sqlite';
  public static readonly codeFile = 'code.sqlite';

  private scopeSQL = '';
  private scopeValues: any[] = [];

  constructor(directory: string, fileName: string = IndexStore.mainFile) {
    this.directory = directory;
    if (!fs.existsSync(directory)) {
      fs.mkdirSync(directory, { recursive: true });
    }
    this.file = path.join(directory, fileName);
    this.db = new Database(this.file);
    this.migrate();
  }

  public set scope(roots: string[] | null) {
    if (!roots) {
      this.scopeSQL = '';
      this.scopeValues = [];
      return;
    }
    if (roots.length === 0) {
      this.scopeSQL = ' AND 0';
      this.scopeValues = [];
      return;
    }
    const clauses = roots.map(() => '(path >= ? AND path < ?)');
    this.scopeSQL = ` AND (${clauses.join(' OR ')})`;
    this.scopeValues = roots.flatMap((root) => {
      const prefix = root.endsWith(path.sep) ? root : root + path.sep;
      return [prefix, prefix + '\uFFFF'];
    });
  }

  private migrate(): void {
    let version = 0;
    const versionRow = this.db.queryFirst<{ user_version: number }>('PRAGMA user_version');
    if (versionRow) {
      version = versionRow.user_version;
    }

    if (version > IndexStore.schemaVersion) {
      throw new Error(`This index was made by a newer DigUp (schema ${version})`);
    }

    if (version === IndexStore.schemaVersion) {
      return;
    }

    this.db.transaction(() => {
      if (version === 0) {
        this.createSchema();
      }
      this.db.execute(`PRAGMA user_version = ${IndexStore.schemaVersion};`);
    });
  }

  private createSchema(): void {
    this.db.execute(`
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
      CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(
        name,
        body,
        original UNINDEXED,
        tokenize = "porter unicode61"
      );
    `);
  }

  public getMeta(key: string): string | null {
    const row = this.db.queryFirst<{ value: string }>('SELECT value FROM meta WHERE key = ?', [key]);
    return row ? row.value : null;
  }

  public setMeta(key: string, value: string | null): void {
    if (value !== null) {
      this.db.run(
        'INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value',
        [key, value]
      );
    } else {
      this.db.run('DELETE FROM meta WHERE key = ?', [key]);
    }
  }

  public filesUnder(roots: string[]): FileRecord[] {
    const out: FileRecord[] = [];
    for (const root of roots) {
      const prefix = root.endsWith(path.sep) ? root : root + path.sep;
      const rows = this.db.query<any>(
        'SELECT id, path, kind, size, mtime, inode, device, state, detail, info, indexed_at FROM files WHERE path >= ? AND path < ?',
        [prefix, prefix + '\uFFFF']
      );
      for (const row of rows) {
        out.push(this.formatRecord(row));
      }
    }
    return out;
  }

  public insert(file: Candidate): number {
    this.db.run(
      'INSERT INTO files(path, kind, size, mtime, inode, device) VALUES(?, ?, ?, ?, ?, ?)',
      [file.path, file.kind, file.size, file.mtime, file.inode, file.device]
    );
    const id = Number(this.db.lastInsertRowid);
    this.addNameRow(id, file.path);
    return id;
  }

  public requeue(id: number, file: Candidate): void {
    this.deleteSegments(id, true);
    this.db.run(
      `UPDATE files SET kind = ?, size = ?, mtime = ?, inode = ?, device = ?, state = 'pending', detail = NULL, info = NULL, indexed_at = NULL WHERE id = ?`,
      [file.kind, file.size, file.mtime, file.inode, file.device, id]
    );
  }

  public move(id: number, newPath: string): void {
    this.db.run('UPDATE files SET path = ? WHERE id = ?', [newPath, id]);
    this.db.run(
      `UPDATE fts SET name = ?, body = ? WHERE rowid IN (SELECT id FROM segments WHERE file = ? AND kind = 'name')`,
      [
        SearchText.normalized(IndexStore.nameText(newPath)),
        SearchText.normalized(IndexStore.folderText(newPath)),
        id
      ]
    );
  }

  public delete(id: number): void {
    this.db.run('DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file = ?)', [id]);
    this.db.run('DELETE FROM files WHERE id = ?', [id]);
  }

  public prune(keepingUnder: string[]): number {
    const prefixes = keepingUnder.map((r) => (r.endsWith(path.sep) ? r : r + path.sep));
    const all = this.db.query<{ id: number; path: string }>('SELECT id, path FROM files');
    const toDelete: number[] = [];

    for (const row of all) {
      if (!prefixes.some((p) => row.path.startsWith(p))) {
        toDelete.push(row.id);
      }
    }

    if (toDelete.length === 0) return 0;

    this.db.transaction(() => {
      for (const id of toDelete) {
        this.delete(id);
      }
    });

    return toDelete.length;
  }

  public resetAll(): void {
    this.db.transaction(() => {
      this.db.run("DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE kind != 'name')");
      this.db.run("DELETE FROM segments WHERE kind != 'name'");
      this.db.run("UPDATE files SET state = 'pending', detail = NULL, info = NULL, indexed_at = NULL");
      this.setMeta('fingerprint', null);
    });
  }

  public pending(limit?: number | null, onlyIds?: Set<number> | null): FileRecord[] {
    let filter = '';
    const params: any[] = [...this.scopeValues];

    if (onlyIds) {
      if (onlyIds.size === 0) return [];
      filter = ` AND id IN (${Array.from(onlyIds).join(',')})`;
    }

    let limitClause = '';
    if (limit && limit > 0) {
      limitClause = ` LIMIT ?`;
      params.push(limit);
    }

    const orderSql = `
      CASE kind WHEN 'screenshot' THEN 0 WHEN 'image' THEN 1 WHEN 'pdf' THEN 2 WHEN 'doc' THEN 3
      WHEN 'audio' THEN 4 WHEN 'video' THEN 5 ELSE 6 END, mtime DESC
    `;

    const sql = `SELECT id, path, kind, size, mtime, inode, device, state, detail, info, indexed_at FROM files WHERE state = 'pending'${filter}${this.scopeSQL} ORDER BY ${orderSql}${limitClause}`;
    const rows = this.db.query<any>(sql, params);
    return rows.map((r) => this.formatRecord(r));
  }

  public pendingCount(): number {
    const sql = `SELECT COUNT(*) as count FROM files WHERE state = 'pending'${this.scopeSQL}`;
    const row = this.db.queryFirst<{ count: number }>(sql, this.scopeValues);
    return row ? row.count : 0;
  }

  public unfinished(): FileRecord[] {
    const orderSql = `
      CASE kind WHEN 'screenshot' THEN 0 WHEN 'image' THEN 1 WHEN 'pdf' THEN 2 WHEN 'doc' THEN 3
      WHEN 'audio' THEN 4 WHEN 'video' THEN 5 ELSE 6 END, mtime DESC
    `;
    const sql = `SELECT id, path, kind, size, mtime, inode, device, state, detail, info, indexed_at FROM files WHERE state = 'partial'${this.scopeSQL} ORDER BY ${orderSql}`;
    const rows = this.db.query<any>(sql, this.scopeValues);
    return rows.map((r) => this.formatRecord(r));
  }

  public unfinishedCount(): { files: number; left: number } {
    const sql = `
      SELECT COUNT(*) as fileCount, 
             SUM(MAX(0, CAST(json_extract(info, '$.read_end') AS INTEGER) - CAST(json_extract(info, '$.read_to') AS INTEGER))) as leftCount
      FROM files WHERE state = 'partial'${this.scopeSQL}
    `;
    const row = this.db.queryFirst<{ fileCount: number; leftCount: number | null }>(sql, this.scopeValues);
    return {
      files: row ? row.fileCount : 0,
      left: row && row.leftCount ? Number(row.leftCount) : 0
    };
  }

  public finish(id: number, state: string, detail?: string | null, info?: Record<string, any>): void {
    const infoJson = info && Object.keys(info).length > 0 ? JSON.stringify(info) : null;
    this.db.run(
      'UPDATE files SET state = ?, detail = ?, info = ?, indexed_at = ? WHERE id = ?',
      [state, detail || null, infoJson, Date.now() / 1000, id]
    );
  }

  public addSegment(params: {
    file: number;
    kind: SegmentKind;
    modality: Modality;
    loc?: number | null;
    locEnd?: number | null;
    excerpt?: string | null;
    text?: string | null;
    vector?: number[] | Float32Array | null;
  }): number {
    const vecBlob = params.vector ? Vectors.toHalf(params.vector) : null;
    this.db.run(
      'INSERT INTO segments(file, kind, modality, loc, loc_end, excerpt, vec) VALUES(?, ?, ?, ?, ?, ?, ?)',
      [
        params.file,
        params.kind,
        params.vector ? params.modality : 'none',
        params.loc ?? null,
        params.locEnd ?? null,
        params.excerpt ?? null,
        vecBlob
      ]
    );

    const segmentId = Number(this.db.lastInsertRowid);

    if (params.text && params.text.trim().length > 0) {
      const normalized = SearchText.normalized(params.text);
      this.db.run(
        'INSERT INTO fts(rowid, name, body, original) VALUES(?, ?, ?, ?)',
        [segmentId, '', normalized, normalized === params.text ? null : params.text]
      );
    }

    return segmentId;
  }

  private addNameRow(fileId: number, filePath: string): void {
    this.db.run(`INSERT INTO segments(file, kind, modality) VALUES(?, 'name', 'none')`, [fileId]);
    const segId = Number(this.db.lastInsertRowid);
    this.db.run(
      'INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)',
      [
        segId,
        SearchText.normalized(IndexStore.nameText(filePath)),
        SearchText.normalized(IndexStore.folderText(filePath))
      ]
    );
  }

  private deleteSegments(fileId: number, keepingName: boolean): void {
    const filter = keepingName ? " AND kind != 'name'" : '';
    this.db.run(`DELETE FROM fts WHERE rowid IN (SELECT id FROM segments WHERE file = ?${filter})`, [fileId]);
    this.db.run(`DELETE FROM segments WHERE file = ?${filter}`, [fileId]);
  }

  public static nameText(filePath: string): string {
    return path.basename(filePath);
  }

  public static folderText(filePath: string): string {
    const home = os.homedir();
    let folder = path.dirname(filePath);
    if (folder.startsWith(home)) {
      folder = folder.slice(home.length);
    }
    return folder.split(/[\\/]+/).filter(Boolean).join(' ');
  }

  public counts(): Counts {
    const counts: Counts = {
      byKindAndState: {},
      vectors: 0,
      segments: 0,
      unfinished: 0,
      left: 0
    };

    const kindRows = this.db.query<{ kind: string; state: string; count: number }>(
      `SELECT kind, state, COUNT(*) as count FROM files WHERE 1${this.scopeSQL} GROUP BY kind, state`,
      this.scopeValues
    );

    for (const r of kindRows) {
      if (!counts.byKindAndState[r.kind]) {
        counts.byKindAndState[r.kind] = {};
      }
      counts.byKindAndState[r.kind][r.state] = r.count;
    }

    const unfin = this.unfinishedCount();
    counts.unfinished = unfin.files;
    counts.left = unfin.left;

    const inScope = this.scopeSQL ? ` AND file IN (SELECT id FROM files WHERE 1${this.scopeSQL})` : '';
    const segRow = this.db.queryFirst<{ segCount: number; vecCount: number }>(
      `SELECT COUNT(*) as segCount, COUNT(vec) as vecCount FROM segments WHERE kind != 'name'${inScope}`,
      this.scopeValues
    );

    if (segRow) {
      counts.segments = segRow.segCount;
      counts.vectors = segRow.vecCount;
    }

    return counts;
  }

  private formatRecord(row: any): FileRecord {
    let parsedInfo: Record<string, any> | null = null;
    if (row.info) {
      try {
        parsedInfo = JSON.parse(row.info);
      } catch {}
    }
    return {
      id: row.id,
      path: row.path,
      kind: row.kind as FileKind,
      size: row.size,
      mtime: row.mtime,
      inode: row.inode,
      device: row.device,
      state: row.state,
      detail: row.detail,
      info: parsedInfo,
      indexed_at: row.indexed_at
    };
  }
}
