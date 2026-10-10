import chokidar, { FSWatcher } from 'chokidar';
import path from 'path';

export interface WatcherCallbacks {
  onChange: (changedPaths: string[]) => void;
}

export class FolderWatcher {
  private watcher: FSWatcher | null = null;
  private watchedFolders: string[] = [];
  private excludedFolders: string[] = [];
  private debounceTimer: NodeJS.Timeout | null = null;
  private pendingChanges = new Set<string>();
  private debounceMs: number;
  private onChanges: (paths: string[]) => void;

  constructor(debounceMs = 1500, onChanges: (paths: string[]) => void) {
    this.debounceMs = debounceMs;
    this.onChanges = onChanges;
  }

  public setFolders(folders: string[], excluded: string[] = []): void {
    this.watchedFolders = folders;
    this.excludedFolders = excluded;
    this.restart();
  }

  public restart(): void {
    this.stop();

    if (this.watchedFolders.length === 0) {
      return;
    }

    const ignored = [
      /(^|[/\\])\../, // ignore hidden files
      '**/node_modules/**',
      '**/.git/**',
      '**/venv/**',
      '**/.venv/**',
      ...this.excludedFolders.map((exc) => path.join(exc, '**'))
    ];

    this.watcher = chokidar.watch(this.watchedFolders, {
      ignored,
      persistent: true,
      ignoreInitial: true,
      awaitWriteFinish: {
        stabilityThreshold: 1000,
        pollInterval: 100
      }
    });

    const handleChange = (filePath: string) => {
      this.pendingChanges.add(filePath);
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
      }
      this.debounceTimer = setTimeout(() => {
        this.flush();
      }, this.debounceMs);
    };

    this.watcher.on('add', handleChange);
    this.watcher.on('change', handleChange);
    this.watcher.on('unlink', handleChange);
  }

  private flush(): void {
    if (this.pendingChanges.size === 0) return;
    const paths = Array.from(this.pendingChanges);
    this.pendingChanges.clear();
    this.onChanges(paths);
  }

  public stop(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    if (this.watcher) {
      this.watcher.close();
      this.watcher = null;
    }
    this.pendingChanges.clear();
  }
}
