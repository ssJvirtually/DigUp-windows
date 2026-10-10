import DatabaseConstructor, { Database as BetterSqliteDatabase, Statement as BetterSqliteStatement } from 'better-sqlite3';
import fs from 'fs';
import path from 'path';

export class Database {
  private db: BetterSqliteDatabase;
  public readonly path: string;

  constructor(filePath: string) {
    this.path = filePath;
    const dir = path.dirname(filePath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }

    this.db = new DatabaseConstructor(filePath, {
      timeout: 5000
    });

    this.db.pragma('journal_mode = WAL');
    this.db.pragma('synchronous = NORMAL');
    this.db.pragma('foreign_keys = ON');
    this.db.pragma('busy_timeout = 5000');
  }

  public execute(sql: string): void {
    this.db.exec(sql);
  }

  public prepare(sql: string): BetterSqliteStatement {
    return this.db.prepare(sql);
  }

  public run(sql: string, params: any[] = []): any {
    return this.db.prepare(sql).run(...params);
  }

  public query<T = any>(sql: string, params: any[] = []): T[] {
    return this.db.prepare(sql).all(...params) as T[];
  }

  public queryFirst<T = any>(sql: string, params: any[] = []): T | undefined {
    return this.db.prepare(sql).get(...params) as T | undefined;
  }

  public transaction<T>(fn: () => T): T {
    return this.db.transaction(fn)();
  }

  public close(): void {
    this.db.close();
  }

  public get lastInsertRowid(): number | bigint {
    const row = this.db.prepare('SELECT last_insert_rowid() as id').get() as { id: number };
    return row.id;
  }

  public pragma(pragmaStr: string, simplify = true): any {
    return this.db.pragma(pragmaStr, { simple: simplify });
  }
}
