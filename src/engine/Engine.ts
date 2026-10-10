import path from 'path';
import fs from 'fs';
import { IndexStore } from './IndexStore.js';
import { Searcher } from './Searcher.js';
import { Indexer } from './Indexer.js';
import { FolderWatcher } from './FolderWatcher.js';
import { WorkerClient } from '../worker/WorkerClient.js';
import { ModelFiles } from '../worker/ModelFiles.js';
import {
  IndexOptions,
  defaultIndexOptions,
  FileKind,
  SearchHit,
  CodeQuery,
  Prompts
} from '../shared/types.js';
import { getIndexDir } from '../shared/constants.js';

export interface EngineStatus {
  activity: 'starting' | 'idle' | 'syncing' | 'indexing' | 'finishing' | 'failed';
  doneFiles: number;
  totalFiles: number;
  files: number;
  searchable: number;
  pending: number;
  vectors: number;
  paused: boolean;
  encoderReady: boolean;
  code: {
    files: number;
    searchable: number;
    pending: number;
    vectors: number;
  };
}

export class Engine {
  public readonly indexDir: string;
  public readonly options: IndexOptions;
  public readonly codeOptions: IndexOptions;

  public mainStore: IndexStore;
  public codeStore: IndexStore;

  public mainSearcher: Searcher;
  public codeSearcher: Searcher;

  public indexWorker: WorkerClient;
  public queryEncoder: WorkerClient;

  public mainIndexer: Indexer;
  public codeIndexer: Indexer;

  private watcher: FolderWatcher;
  private codeWatcher: FolderWatcher;

  private roots: string[] = [];
  private excludedFolders: string[] = [];
  private codeRoots: string[] = [];
  private isPaused = false;
  private isIndexing = false;

  private onStatusChange?: (status: EngineStatus) => void;

  constructor(options?: Partial<IndexOptions>, onStatusChange?: (status: EngineStatus) => void) {
    this.indexDir = getIndexDir();
    this.onStatusChange = onStatusChange;

    this.options = { ...defaultIndexOptions(), ...options, code: false };
    this.codeOptions = { ...defaultIndexOptions(), ...options, code: true };

    this.mainStore = new IndexStore(this.indexDir, IndexStore.mainFile);
    this.codeStore = new IndexStore(this.indexDir, IndexStore.codeFile);

    this.mainSearcher = new Searcher(this.mainStore);
    this.codeSearcher = new Searcher(this.codeStore);

    // Index worker runs during indexing; query encoder is lightweight and started on-demand
    this.indexWorker = new WorkerClient(60000);
    this.queryEncoder = new WorkerClient(15000);

    this.mainIndexer = new Indexer(this.mainStore, this.options, this.indexWorker);
    this.codeIndexer = new Indexer(this.codeStore, this.codeOptions, this.indexWorker);

    this.watcher = new FolderWatcher(1500, (changed) => this.handleFolderChange(changed, false));
    this.codeWatcher = new FolderWatcher(5000, (changed) => this.handleFolderChange(changed, true));
  }

  public async start(): Promise<void> {
    const workerScript = path.join(__dirname, '../worker/digup_worker.py');
    try {
      await this.queryEncoder.start(workerScript, ['--text-only']);
    } catch (err) {
      console.error('Failed to start query encoder:', err);
    }
    this.emitStatus('idle');
  }

  public setFolders(roots: string[], excluded: string[] = []): void {
    this.roots = roots;
    this.excludedFolders = excluded;
    this.watcher.setFolders(roots, excluded);
    this.triggerSync();
  }

  public setCodeFolders(roots: string[]): void {
    this.codeRoots = roots;
    this.codeWatcher.setFolders(roots);
    this.triggerSync(true);
  }

  public setPaused(paused: boolean): void {
    this.isPaused = paused;
    this.emitStatus(paused ? 'idle' : 'syncing');
  }

  public async search(
    query: string,
    limit = 20,
    kinds?: Set<FileKind>
  ): Promise<SearchHit[]> {
    const codeQueryText = CodeQuery.text(query);
    const isCode = codeQueryText !== null;
    const searchText = isCode ? codeQueryText : query;

    if (!searchText) return [];

    let queryVector: number[] | null = null;
    if (this.queryEncoder.isReady) {
      try {
        const prompt = isCode ? Prompts.codeQuery(searchText) : Prompts.query(searchText);
        const res = await this.queryEncoder.embedTexts([prompt]);
        queryVector = res[0]?.vector || null;
      } catch (err) {
        console.error('Query embedding error:', err);
      }
    }

    if (isCode) {
      return this.codeSearcher.search({
        text: searchText,
        queryVector,
        limit,
        kinds: new Set<FileKind>(['code'])
      });
    }

    return this.mainSearcher.search({
      text: searchText,
      queryVector,
      limit,
      kinds
    });
  }

  public async triggerSync(onlyCode = false): Promise<void> {
    if (this.isPaused || this.isIndexing) return;
    this.isIndexing = true;
    this.emitStatus('syncing');

    try {
      const workerScript = path.join(__dirname, '../worker/digup_worker.py');
      if (!this.indexWorker.isReady) {
        await this.indexWorker.start(workerScript);
      }

      if (!onlyCode && this.roots.length > 0) {
        await this.mainIndexer.sync(this.roots);
        await this.mainIndexer.run(
          null,
          null,
          () => this.isPaused,
          (e) => {
            this.emitStatus('indexing', e.done, e.total);
          }
        );
        this.mainSearcher.reload();
      }

      if (this.codeRoots.length > 0) {
        await this.codeIndexer.sync(this.codeRoots);
        await this.codeIndexer.run(
          null,
          null,
          () => this.isPaused,
          (e) => {
            this.emitStatus('indexing', e.done, e.total);
          }
        );
        this.codeSearcher.reload();
      }
    } catch (err) {
      console.error('Indexing failed:', err);
      this.emitStatus('failed');
    } finally {
      this.isIndexing = false;
      this.emitStatus('idle');
    }
  }

  private handleFolderChange(changedPaths: string[], isCode: boolean): void {
    if (this.isPaused) return;
    this.triggerSync(isCode);
  }

  public getStatus(): EngineStatus {
    const mainCounts = this.mainStore.counts();
    const codeCounts = this.codeStore.counts();

    let mainDone = 0;
    let mainPending = 0;
    for (const [k, states] of Object.entries(mainCounts.byKindAndState)) {
      mainDone += (states['done'] || 0) + (states['partial'] || 0);
      mainPending += states['pending'] || 0;
    }

    return {
      activity: this.isIndexing ? 'indexing' : 'idle',
      doneFiles: mainDone,
      totalFiles: mainDone + mainPending,
      files: mainDone + mainPending,
      searchable: mainDone,
      pending: mainPending,
      vectors: mainCounts.vectors,
      paused: this.isPaused,
      encoderReady: this.queryEncoder.isReady,
      code: {
        files: codeCounts.segments,
        searchable: codeCounts.segments,
        pending: this.codeStore.pendingCount(),
        vectors: codeCounts.vectors
      }
    };
  }

  private emitStatus(
    activity: EngineStatus['activity'],
    done = 0,
    total = 0
  ): void {
    if (this.onStatusChange) {
      const status = this.getStatus();
      status.activity = activity;
      status.doneFiles = done;
      status.totalFiles = total;
      this.onStatusChange(status);
    }
  }

  public close(): void {
    this.watcher.stop();
    this.codeWatcher.stop();
    this.indexWorker.close();
    this.queryEncoder.close();
    this.mainStore.db.close();
    this.codeStore.db.close();
  }
}
