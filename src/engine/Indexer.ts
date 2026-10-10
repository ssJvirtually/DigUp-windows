import fs from 'fs';
import path from 'path';
import { IndexStore } from './IndexStore.js';
import { IndexOptions, FileRecord, FileKind, Prompts } from '../shared/types.js';
import { Crawler } from './Crawler.js';
import { WorkerClient } from '../worker/WorkerClient.js';
import { ImageExtractor } from './extractors/ImageExtractor.js';
import { OCRExtractor } from './extractors/OCRExtractor.js';
import { PDFExtractor } from './extractors/PDFExtractor.js';
import { DocExtractor } from './extractors/DocExtractor.js';
import { CodeExtractor } from './extractors/CodeExtractor.js';
import { SearchText } from './SearchText.js';

export interface IndexerEvent {
  done: number;
  total: number;
  path: string;
  kind: FileKind;
  state: string;
  seconds: number;
  note?: string;
}

export interface SyncSummary {
  added: number;
  changed: number;
  moved: number;
  removed: number;
  unchanged: number;
  queued: number[];
}

export class Indexer {
  public readonly store: IndexStore;
  public readonly options: IndexOptions;
  public readonly worker: WorkerClient;

  constructor(store: IndexStore, options: IndexOptions, worker: WorkerClient) {
    this.store = store;
    this.options = options;
    this.worker = worker;
  }

  public async sync(roots: string[]): Promise<SyncSummary> {
    const crawl = Crawler.crawl(roots, this.options);
    const existing = this.store.filesUnder(roots);

    const byPath = new Map<string, FileRecord>();
    const byInode = new Map<string, FileRecord>();
    for (const f of existing) {
      byPath.set(f.path, f);
      byInode.set(`${f.device}:${f.inode}`, f);
    }

    const seen = new Set(crawl.candidates.map((c) => c.path));
    const movedFrom = new Set<string>();

    const summary: SyncSummary = {
      added: 0,
      changed: 0,
      moved: 0,
      removed: 0,
      unchanged: 0,
      queued: []
    };

    this.store.db.transaction(() => {
      for (const file of crawl.candidates) {
        const existingRow = byPath.get(file.path);
        if (existingRow) {
          if (
            existingRow.size !== file.size ||
            Math.abs(existingRow.mtime - file.mtime) > 0.001 ||
            existingRow.kind !== file.kind
          ) {
            this.store.requeue(existingRow.id, file);
            summary.changed++;
            summary.queued.push(existingRow.id);
          } else {
            summary.unchanged++;
          }
        } else {
          const movedRow = byInode.get(`${file.device}:${file.inode}`);
          if (
            movedRow &&
            !seen.has(movedRow.path) &&
            !movedFrom.has(movedRow.path) &&
            movedRow.size === file.size &&
            Math.abs(movedRow.mtime - file.mtime) <= 0.001
          ) {
            this.store.move(movedRow.id, file.path);
            movedFrom.add(movedRow.path);
            summary.moved++;
          } else {
            const id = this.store.insert(file);
            summary.added++;
            summary.queued.push(id);
          }
        }
      }

      // Check deletions
      for (const f of existing) {
        if (!seen.has(f.path) && !movedFrom.has(f.path)) {
          if (!fs.existsSync(f.path)) {
            this.store.delete(f.id);
            summary.removed++;
          }
        }
      }
    });

    return summary;
  }

  public async run(
    limit?: number | null,
    onlyIds?: Set<number> | null,
    shouldStop: () => boolean = () => false,
    onEvent?: (e: IndexerEvent) => void
  ): Promise<Record<string, number>> {
    const pendingFiles = this.store.pending(limit, onlyIds);
    const total = pendingFiles.length;
    let done = 0;
    const stats: Record<string, number> = {};

    for (const file of pendingFiles) {
      if (shouldStop()) break;
      const started = Date.now();

      try {
        await this.indexFile(file);
        done++;
        stats[file.kind] = (stats[file.kind] || 0) + 1;

        if (onEvent) {
          onEvent({
            done,
            total,
            path: file.path,
            kind: file.kind,
            state: 'done',
            seconds: (Date.now() - started) / 1000
          });
        }
      } catch (err: any) {
        this.store.finish(file.id, 'failed', String(err.message || err));
        done++;
        if (onEvent) {
          onEvent({
            done,
            total,
            path: file.path,
            kind: file.kind,
            state: 'failed',
            seconds: (Date.now() - started) / 1000,
            note: String(err)
          });
        }
      }
    }

    return stats;
  }

  private async indexFile(file: FileRecord): Promise<void> {
    if (!fs.existsSync(file.path)) {
      this.store.delete(file.id);
      return;
    }

    switch (file.kind) {
      case 'screenshot':
      case 'image': {
        const prep = ImageExtractor.prepare(file.path, this.options.minImageSide);
        let ocrText = '';
        if (file.kind === 'screenshot') {
          ocrText = await OCRExtractor.recognizeText(file.path);
        }

        const budget = file.kind === 'screenshot' ? this.options.screenshotBudget : this.options.photoBudget;
        const embResults = await this.worker.embedImages([prep.path], budget);
        const vec = embResults[0]?.vector || null;

        this.store.addSegment({
          file: file.id,
          kind: 'image',
          modality: 'image',
          vector: vec
        });

        if (ocrText && ocrText.trim().length > 0) {
          this.store.addSegment({
            file: file.id,
            kind: 'ocr',
            modality: 'none',
            text: ocrText
          });
        }

        this.store.finish(file.id, 'done', null, {
          width: prep.width,
          height: prep.height,
          taken: prep.taken || undefined
        });
        break;
      }

      case 'doc': {
        const text = await DocExtractor.text(file.path);
        const chunks = DocExtractor.chunks(text, 1800, 200, this.options.docStep);

        if (chunks.length === 0) {
          this.store.finish(file.id, 'done', 'empty');
          return;
        }

        const promptInputs = chunks.map((c) =>
          Prompts.document(path.basename(file.path), c)
        );
        const embeddings = await this.worker.embedTexts(promptInputs);

        for (let i = 0; i < chunks.length; i++) {
          const chunk = chunks[i];
          const vec = SearchText.hasWords(chunk) ? embeddings[i]?.vector || null : null;
          this.store.addSegment({
            file: file.id,
            kind: 'chunk',
            modality: 'text',
            loc: i + 1,
            excerpt: DocExtractor.excerpt(chunk),
            text: chunk,
            vector: vec
          });
        }

        const isPartial = text.length > 64000;
        this.store.finish(
          file.id,
          isPartial ? 'partial' : 'done',
          isPartial ? `read to chunk ${chunks.length}` : null,
          {
            chars: text.length,
            chunks: chunks.length,
            read_to: chunks.length,
            read_end: Math.min(5000, Math.ceil(text.length / 1600))
          }
        );
        break;
      }

      case 'pdf': {
        const extracted = await PDFExtractor.pages(
          file.path,
          () => ({ start: 0, end: this.options.pdfStep }),
          this.options.minPageText
        );

        if (extracted.pages.length === 0) {
          this.store.finish(file.id, 'done', 'no pages');
          return;
        }

        const textPages = extracted.pages.filter((p) => p.text.trim().length > 0);
        const promptInputs = textPages.map((p) =>
          Prompts.document(extracted.title || path.basename(file.path), p.text)
        );
        const embeddings = promptInputs.length > 0 ? await this.worker.embedTexts(promptInputs) : [];

        let embIdx = 0;
        for (const page of extracted.pages) {
          let vec: number[] | null = null;
          if (page.text.trim().length > 0) {
            vec = embeddings[embIdx]?.vector || null;
            embIdx++;
          }

          this.store.addSegment({
            file: file.id,
            kind: 'page',
            modality: 'text',
            loc: page.number,
            excerpt: DocExtractor.excerpt(page.text),
            text: page.text,
            vector: vec
          });
        }

        const isPartial = extracted.total > this.options.pdfStep;
        this.store.finish(
          file.id,
          isPartial ? 'partial' : 'done',
          isPartial ? `read to page ${extracted.pages.length}` : null,
          {
            pages: extracted.total,
            read_to: extracted.pages.length,
            read_end: Math.min(this.options.maxPages, extracted.total)
          }
        );
        break;
      }

      case 'code': {
        const codeFile = CodeExtractor.read(file.path, this.options);
        const promptInputs = codeFile.pieces.map((p) =>
          Prompts.document(codeFile.title, p.text)
        );
        const embeddings = await this.worker.embedTexts(promptInputs);

        for (let i = 0; i < codeFile.pieces.length; i++) {
          const piece = codeFile.pieces[i];
          const vec = embeddings[i]?.vector || null;

          this.store.addSegment({
            file: file.id,
            kind: codeFile.cells != null ? 'cells' : 'lines',
            modality: 'text',
            loc: piece.first,
            locEnd: piece.last,
            excerpt: CodeExtractor.summary(piece.text),
            text: piece.text,
            vector: vec
          });
        }

        this.store.finish(file.id, 'done', null, {
          lines: codeFile.lines,
          pieces: codeFile.pieces.length,
          repo: codeFile.repo || undefined
        });
        break;
      }

      case 'audio': {
        // Audio embedding
        const embResults = await this.worker.embedAudio([file.path]);
        const vec = embResults[0]?.vector || null;

        this.store.addSegment({
          file: file.id,
          kind: 'audio',
          modality: 'audio',
          loc: 0,
          vector: vec
        });

        this.store.finish(file.id, 'done');
        break;
      }

      case 'video': {
        // Video keyframes / audio
        const embResults = await this.worker.embedAudio([file.path]);
        const vec = embResults[0]?.vector || null;

        this.store.addSegment({
          file: file.id,
          kind: 'frame',
          modality: 'image',
          loc: 0,
          vector: vec
        });

        this.store.finish(file.id, 'done');
        break;
      }
    }
  }

  public async finishLongFiles(
    shouldStop: () => boolean = () => false,
    onEvent?: (e: IndexerEvent) => void
  ): Promise<void> {
    const unfinishedFiles = this.store.unfinished();
    for (const file of unfinishedFiles) {
      if (shouldStop()) break;
      // Continue reading remaining pages or chunks
      if (file.kind === 'pdf') {
        const info = (file.info as any) || {};
        const readTo = Number(info.read_to || 30);
        const total = Number(info.pages || 0);
        if (readTo < total) {
          const nextPages = await PDFExtractor.pages(
            file.path,
            () => ({ start: readTo, end: Math.min(total, readTo + 50) }),
            this.options.minPageText
          );

          const promptInputs = nextPages.pages
            .filter((p) => p.text.trim().length > 0)
            .map((p) => Prompts.document(path.basename(file.path), p.text));
          const embeddings = promptInputs.length > 0 ? await this.worker.embedTexts(promptInputs) : [];

          let embIdx = 0;
          for (const page of nextPages.pages) {
            let vec: number[] | null = null;
            if (page.text.trim().length > 0) {
              vec = embeddings[embIdx]?.vector || null;
              embIdx++;
            }

            this.store.addSegment({
              file: file.id,
              kind: 'page',
              modality: 'text',
              loc: page.number,
              excerpt: DocExtractor.excerpt(page.text),
              text: page.text,
              vector: vec
            });
          }

          const newReadTo = readTo + nextPages.pages.length;
          const isDone = newReadTo >= total;
          this.store.finish(
            file.id,
            isDone ? 'done' : 'partial',
            isDone ? null : `read to page ${newReadTo}`,
            {
              ...info,
              read_to: newReadTo
            }
          );
        }
      }
    }
  }
}
