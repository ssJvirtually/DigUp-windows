import path from 'path';
import { IndexStore } from './IndexStore.js';
import { FileKind, SegmentKind, Modality, SearchHit } from '../shared/types.js';
import { Vectors } from './Vectors.js';
import { SearchText } from './SearchText.js';
import { CodeLookup } from './CodeLookup.js';
import { DocExtractor } from './extractors/DocExtractor.js';

interface StoredVectorRow {
  segmentId: number;
  fileId: number;
  modality: Modality;
  vec: Float32Array;
  setsScale: boolean;
}

export class Searcher {
  public readonly store: IndexStore;
  public readonly isCode: boolean;

  private rows: StoredVectorRow[] = [];
  private fileKind = new Map<number, FileKind>();
  public vectorsLoaded = false;
  public vectorSource: string | null = null;

  public static readonly wordWeight = 2.0;
  public static readonly codeWordWeight = 1.0;

  constructor(store: IndexStore, loadVectors = true) {
    this.store = store;
    this.isCode = path.basename(store.file) === IndexStore.codeFile;
    if (loadVectors) {
      this.reload();
    } else {
      this.reloadFiles();
    }
  }

  public get vectorCount(): number {
    return this.rows.length;
  }

  public reload(): void {
    this.rows = [];
    this.reloadFiles();

    const sql = `
      SELECT s.id, s.file, s.modality, s.vec, s.kind, s.loc, f.kind as file_kind, CAST(json_extract(f.info, '$.pages') AS INTEGER) as pages
      FROM segments s JOIN files f ON f.id = s.file
      WHERE s.vec IS NOT NULL ORDER BY s.id
    `;

    const rawRows = this.store.db.query<any>(sql);
    for (const r of rawRows) {
      if (!r.vec || !(r.vec instanceof Buffer)) continue;
      const floatVec = Vectors.fromHalf(r.vec);
      if (floatVec.length !== Vectors.dimension) continue;

      const kind = r.kind;
      const place = r.loc != null ? Number(r.loc) : 0;
      const fileKind = r.file_kind;
      const pages = r.pages != null ? Number(r.pages) : 0;

      // Long file scale rules (matching macOS IndexOptions: bigPDFPages=50, pdfStep=30, docStep=40)
      const later =
        fileKind === 'pdf'
          ? kind !== 'chunk' && pages > 50 && place > 30
          : kind === 'chunk' && place > 40;

      this.rows.push({
        segmentId: r.id,
        fileId: r.file,
        modality: (r.modality as Modality) || 'none',
        vec: floatVec,
        setsScale: !later
      });
    }

    this.vectorsLoaded = true;
  }

  public releaseVectors(): void {
    this.rows = [];
    this.vectorsLoaded = false;
  }

  private reloadFiles(): void {
    this.fileKind.clear();
    const rows = this.store.db.query<{ id: number; kind: string }>('SELECT id, kind FROM files');
    for (const r of rows) {
      this.fileKind.set(r.id, r.kind as FileKind);
    }
    this.vectorSource = this.store.getMeta('vector_source');
  }

  public search(params: {
    text: string;
    queryVector?: number[] | Float32Array | null;
    kinds?: Set<FileKind> | null;
    limit?: number;
    zGate?: number;
    wordWeight?: number;
    prefixLast?: boolean;
  }): SearchHit[] {
    const text = params.text.trim();
    const limit = params.limit || 20;
    const zGate = params.zGate ?? 2.0;
    const wordWeight = params.wordWeight ?? (this.isCode ? Searcher.codeWordWeight : Searcher.wordWeight);

    if (params.queryVector && !this.vectorsLoaded) {
      this.reload();
    }

    const allowed = (fileId: number) => {
      if (!params.kinds) return true;
      const k = this.fileKind.get(fileId);
      return k ? params.kinds.has(k) : false;
    };

    // 1. Vector Meaning Search
    const bestSemantic = new Map<number, { z: number; cosine: number; rowIdx: number }>();

    if (params.queryVector && this.rows.length > 0) {
      const qVec = params.queryVector instanceof Float32Array ? params.queryVector : new Float32Array(params.queryVector);
      const scores = new Float32Array(this.rows.length);

      for (let i = 0; i < this.rows.length; i++) {
        scores[i] = Vectors.dot(qVec, this.rows[i].vec);
      }

      const meaningScores = this.computeMeaningZScores(scores);

      for (let i = 0; i < this.rows.length; i++) {
        const fileId = this.rows[i].fileId;
        if (!allowed(fileId)) continue;

        const z = meaningScores[i];
        const existing = bestSemantic.get(fileId);
        if (!existing || z > existing.z) {
          bestSemantic.set(fileId, { z, cosine: scores[i], rowIdx: i });
        }
      }
    }

    // 2. FTS5 Keyword Search
    const terms = Searcher.terms(text);
    const identifier = this.isCode ? CodeLookup.identifier(text) : null;
    const wordsMatches: Array<{ file: number; segment: number; snippet: string | null }> = [];
    const coverage = new Map<number, number>();

    const patterns = identifier
      ? [CodeLookup.phrase(identifier)]
      : Searcher.patterns(terms, params.prefixLast || false);

    if (patterns.length > 0) {
      const seenFiles = new Set<number>();
      const matchSql = patterns.join(' OR ');

      try {
        const ftsRows = this.store.db.query<any>(
          `SELECT s.file, s.id, snippet(fts, 1, '«', '»', '…', 12) as snip, fts.original 
           FROM fts JOIN segments s ON s.id = fts.rowid 
           WHERE fts MATCH ? ORDER BY bm25(fts, 3.0, 1.0) LIMIT 1000`,
          [matchSql]
        );

        for (const r of ftsRows) {
          const fileId = r.file;
          if (wordsMatches.length >= 100 || !allowed(fileId) || seenFiles.has(fileId)) continue;
          seenFiles.add(fileId);

          const snippet = r.original ? SearchText.snippet(r.original, terms) : r.snip;
          wordsMatches.push({
            file: fileId,
            segment: r.id,
            snippet
          });
        }

        if (wordsMatches.length > 0) {
          const fileList = wordsMatches.map((w) => w.file).join(',');
          for (const pattern of patterns) {
            const covRows = this.store.db.query<{ file: number }>(
              `SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid WHERE fts MATCH ? AND s.file IN (${fileList})`,
              [pattern]
            );
            for (const r of covRows) {
              coverage.set(r.file, (coverage.get(r.file) || 0) + 1 / patterns.length);
            }
          }
        }
      } catch {}
    }

    // 3. Candidate Scoring & Hybrid Fusion
    const lookup = Searcher.isLookup(terms) || identifier !== null;
    const [meaningWeight, wordsWeightCalc] = lookup ? [0.5, wordWeight * 2] : [1.0, wordWeight];
    const hinted = Searcher.kindHints(text);

    const candidates = new Set<number>();
    for (const [fileId, sem] of bestSemantic.entries()) {
      if (sem.z >= zGate) candidates.add(fileId);
    }
    for (const [fileId, share] of coverage.entries()) {
      if (share >= 0.5) candidates.add(fileId);
    }

    const scored = new Map<number, number>();
    for (const fileId of candidates) {
      const sem = bestSemantic.get(fileId);
      const semZ = sem ? Math.max(0, sem.z) : 0;
      const cov = coverage.get(fileId) || 0;

      let score = meaningWeight * semZ + wordsWeightCalc * Math.pow(cov, 1.5);
      const kind = this.fileKind.get(fileId);
      if (kind && hinted.has(kind)) {
        score += 1.0;
      }
      scored.set(fileId, score);
    }

    // 4. Sort and Build Hits
    const sortedCandidates = Array.from(scored.entries()).sort((a, b) => b[1] - a[1]);
    const wordRankMap = new Map<number, { rank: number; item: (typeof wordsMatches)[0] }>();
    wordsMatches.forEach((item, idx) => {
      if (!wordRankMap.has(item.file)) {
        wordRankMap.set(item.file, { rank: idx + 1, item });
      }
    });

    const hits: SearchHit[] = [];

    for (const [fileId, score] of sortedCandidates.slice(0, limit)) {
      const semantic = bestSemantic.get(fileId);
      const keyword = wordRankMap.get(fileId);

      const useMeaning = semantic ? semantic.z >= zGate || !keyword : false;
      let segmentID = useMeaning && semantic ? this.rows[semantic.rowIdx].segmentId : keyword?.item.segment;

      if (!segmentID) continue;

      let focus: number | null = null;
      if (this.isCode) {
        const place = this.resolveCodePlace(fileId, segmentID, identifier, text, terms);
        segmentID = place.segmentId;
        focus = place.focus;
      }

      // Fetch file record
      const fileRow = this.store.db.queryFirst<any>(
        'SELECT path, kind, mtime, size, info FROM files WHERE id = ?',
        [fileId]
      );
      if (!fileRow) continue;

      // Fetch segment details
      const segRow = this.store.db.queryFirst<any>(
        'SELECT kind, loc, loc_end, excerpt FROM segments WHERE id = ?',
        [segmentID]
      );

      const segmentKind = (segRow?.kind as SegmentKind) || 'name';
      const loc = segRow?.loc != null ? Number(segRow.loc) : null;
      const locEnd = segRow?.loc_end != null ? Number(segRow.loc_end) : null;
      const storedExcerpt = segRow?.excerpt || null;

      let snippet = keyword && keyword.item.segment === segmentID ? keyword.item.snippet : null;
      if (snippet && snippet.includes('«')) {
        snippet = DocExtractor.excerpt(snippet, 400);
      } else {
        snippet = null;
      }

      let parsedInfo: Record<string, string> = {};
      if (fileRow.info) {
        try {
          parsedInfo = JSON.parse(fileRow.info);
        } catch {}
      }

      hits.push({
        path: fileRow.path,
        kind: fileRow.kind as FileKind,
        score,
        z: semantic ? semantic.z : null,
        cosine: semantic ? semantic.cosine : null,
        keywordRank: keyword ? keyword.rank : null,
        segment: segmentKind,
        segmentID,
        loc,
        locEnd,
        excerpt: snippet || storedExcerpt,
        modified: fileRow.mtime,
        size: fileRow.size,
        info: parsedInfo,
        focus
      });
    }

    return hits;
  }

  private computeMeaningZScores(scores: Float32Array): Float32Array {
    const count = scores.length;
    const means = new Map<Modality, number>();
    const sums = new Map<Modality, number>();
    const counts = new Map<Modality, number>();

    for (let i = 0; i < count; i++) {
      if (!this.rows[i].setsScale) continue;
      const mod = this.rows[i].modality;
      sums.set(mod, (sums.get(mod) || 0) + scores[i]);
      counts.set(mod, (counts.get(mod) || 0) + 1);
    }

    let globalSum = 0;
    let globalCount = 0;
    for (const [mod, s] of sums.entries()) {
      globalSum += s;
      globalCount += counts.get(mod) || 0;
    }
    const globalMean = globalCount > 0 ? globalSum / globalCount : 0;

    for (const mod of ['none', 'text', 'image', 'audio'] as Modality[]) {
      const n = counts.get(mod) || 0;
      if (n >= 20) {
        means.set(mod, sums.get(mod)! / n);
      } else {
        means.set(mod, globalMean);
      }
    }

    // Centered scores
    const centered = new Float64Array(count);
    for (let i = 0; i < count; i++) {
      const mod = this.rows[i].modality;
      centered[i] = scores[i] - (means.get(mod) ?? globalMean);
    }

    // Global deviation across setsScale rows
    let scaleSum = 0;
    let scaleCount = 0;
    for (let i = 0; i < count; i++) {
      if (this.rows[i].setsScale) {
        scaleSum += centered[i];
        scaleCount++;
      }
    }
    const scaleMean = scaleCount > 0 ? scaleSum / scaleCount : 0;

    let squares = 0;
    for (let i = 0; i < count; i++) {
      if (this.rows[i].setsScale) {
        const diff = centered[i] - scaleMean;
        squares += diff * diff;
      }
    }
    const deviation = Math.max(Math.sqrt(scaleCount > 0 ? squares / scaleCount : 0), 0.02);

    const out = new Float32Array(count);
    for (let i = 0; i < count; i++) {
      out[i] = (centered[i] - scaleMean) / deviation;
    }
    return out;
  }

  private resolveCodePlace(
    fileId: number,
    segmentId: number,
    identifier: string[] | null,
    query: string,
    terms: string[]
  ): { segmentId: number; focus: number | null } {
    const name = query.trim().replace(/\(\)$/, '');

    if (identifier) {
      try {
        const rows = this.store.db.query<any>(
          `SELECT s.id, s.loc, coalesce(fts.original, fts.body) as text
           FROM fts JOIN segments s ON s.id = fts.rowid
           WHERE fts MATCH ? AND s.file = ? ORDER BY s.loc LIMIT 300`,
          [CodeLookup.phrase(identifier), fileId]
        );

        let bestDef: { segment: number; line: number; strength: number } | null = null;
        for (const r of rows) {
          if (!r.text) continue;
          const found = CodeLookup.definition(name, r.text, Number(r.loc) || 1);
          if (found && (!bestDef || found.strength > bestDef.strength)) {
            bestDef = { segment: r.id, line: found.line, strength: found.strength };
            if (found.strength >= 2) break;
          }
        }

        if (bestDef) {
          return { segmentId: bestDef.segment, focus: bestDef.line };
        }
      } catch {}
    }

    // Default to first line containing query words
    const segRow = this.store.db.queryFirst<any>(
      `SELECT s.loc, coalesce(fts.original, fts.body) as text FROM fts JOIN segments s ON s.id = fts.rowid WHERE s.id = ?`,
      [segmentId]
    );

    if (segRow?.text) {
      const firstLine = CodeLookup.firstLine(
        identifier ? [name.toLowerCase()] : terms,
        segRow.text,
        Number(segRow.loc) || 1
      );
      return { segmentId, focus: firstLine };
    }

    return { segmentId, focus: null };
  }

  public static isLookup(terms: string[]): boolean {
    return (
      terms.length > 0 &&
      terms.every((t) => /\d/.test(t) && (/[A-Za-z]/.test(t) || t.length >= 4))
    );
  }

  public static strongCount(hits: SearchHit[], ratio = 0.5): number {
    if (hits.length === 0 || hits[0].score <= 0) return hits.length;
    const topScore = hits[0].score;
    let count = 0;
    for (const h of hits) {
      if (h.score >= topScore * ratio) count++;
      else break;
    }
    return count;
  }

  public static readonly stopwords = new Set([
    'a', 'an', 'the', 'of', 'in', 'on', 'at', 'to', 'for', 'with', 'and', 'or', 'my', 'me',
    'that', 'this', 'these', 'those', 'from', 'about', 'where', 'what', 'which', 'who',
    'when', 'is', 'was', 'are', 'were', 'be', 'it', 'its', 'some', 'any', 'find', 'show',
    'file', 'files', 'photo', 'photos', 'picture', 'image', 'images', 'screenshot',
    'screenshots', 'video', 'videos', 'pdf', 'document', 'documents', 'recording',
    'এবং', 'ও', 'এই', 'সেই', 'একটি', 'আমার', 'থেকে', 'জন্য', 'সাথে', 'মধ্যে', 'কোথায়',
    'কোন', 'কী', 'কি', 'যে', 'ছবি', 'স্ক্রিনশট', 'ভিডিও', 'ফাইল', 'পিডিএফ',
    'في', 'من', 'على', 'الى', 'عن', 'مع', 'هذا', 'هذه', 'ذلك', 'التي', 'الذي', 'اين', 'ما',
    'صورة', 'صور', 'لقطة', 'شاشة', 'فيديو', 'ملف', 'مستند'
  ]);

  public static terms(text: string): string[] {
    const seen = new Set<string>();
    const normalized = SearchText.normalized(text).toLowerCase();
    const words = normalized.split(/[^\p{L}\p{N}]+/u).filter(Boolean);

    return words.filter((w) => {
      if (w.length < 2 || this.stopwords.has(w) || seen.has(w)) return false;
      seen.add(w);
      return true;
    });
  }

  public static patterns(terms: string[], prefixLast: boolean): string[] {
    return terms.map((term, index) => {
      const isPrefix = (prefixLast && index === terms.length - 1) || SearchText.inflects(term);
      return `"${term}"${isPrefix ? '*' : ''}`;
    });
  }

  public static readonly kindWords: Record<string, FileKind> = {
    screenshot: 'screenshot',
    screenshots: 'screenshot',
    photo: 'image',
    photos: 'image',
    picture: 'image',
    pictures: 'image',
    image: 'image',
    images: 'image',
    pdf: 'pdf',
    pdfs: 'pdf',
    paper: 'pdf',
    papers: 'pdf',
    document: 'doc',
    documents: 'doc',
    note: 'doc',
    notes: 'doc',
    video: 'video',
    videos: 'video',
    clip: 'video',
    movie: 'video',
    audio: 'audio',
    song: 'audio',
    podcast: 'audio',
    voice: 'audio',
    sound: 'audio'
  };

  public static kindHints(text: string): Set<FileKind> {
    const hints = new Set<FileKind>();
    const words = SearchText.normalized(text).toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    for (const w of words) {
      const kind = this.kindWords[w];
      if (kind) hints.add(kind);
    }
    return hints;
  }
}
