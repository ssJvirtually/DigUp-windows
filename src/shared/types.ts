// DigUp Core Types - matching macOS DigUpKit specifications

export type FileKind = 'screenshot' | 'image' | 'pdf' | 'doc' | 'audio' | 'video' | 'code';

export type SegmentKind = 
  | 'name'        // file name + folder names (keyword search only)
  | 'image'       // the image itself
  | 'ocr'         // text read from a screenshot
  | 'page'        // a PDF page's text
  | 'page_image'  // a PDF page rendered as an image (scans, slides)
  | 'chunk'       // a piece of a document's text
  | 'audio'       // a window of audio (a file or a video's soundtrack)
  | 'frame'       // a video keyframe
  | 'lines'       // a stretch of a code file: loc to loc_end are first and last lines
  | 'cells';      // a stretch of a notebook: loc to loc_end are first and last cells

export type Modality = 'none' | 'text' | 'image' | 'audio';

export interface FileRecord {
  id: number;
  path: string;
  kind: FileKind;
  size: number;
  mtime: number;
  inode: number;
  device: number;
  state: 'pending' | 'done' | 'partial' | 'skipped' | 'failed';
  detail?: string | null;
  info?: Record<string, any> | null;
  indexed_at?: number | null;
}

export interface Candidate {
  path: string;
  kind: FileKind;
  size: number;
  mtime: number;
  inode: number;
  device: number;
}

export interface CrawlReport {
  candidates: Candidate[];
  skipped: Record<string, number>;
  skippedFolders: Record<string, number>;
  datasets: Record<string, number>;
}

export interface IndexOptions {
  code: boolean;
  codeStretch: number;
  maxCodeBytes: number;
  maxNotebookBytes: number;
  includeCode: boolean;
  includeDatasets: boolean;
  minImageSide: number;
  bigPDFPages: number;
  pdfStep: number;
  docStep: number;
  maxPages: number;
  maxChunks: number;
  minPageText: number;
  photoBudget: number;       // 140
  screenshotBudget: number;  // 280
  pageBudget: number;        // 280
  frameBudget: number;       // 70
  frameInterval: number;     // 3.0s
  audioWindow: number;       // 30.0s
  audioHop: number;          // 25.0s
  excludedFolders: string[];
  excludedExtensions: Set<string>;
}

export function defaultIndexOptions(): IndexOptions {
  return {
    code: false,
    codeStretch: 1800,
    maxCodeBytes: 1 << 20,       // 1 MB
    maxNotebookBytes: 20 << 20,  // 20 MB
    includeCode: false,
    includeDatasets: false,
    minImageSide: 256,
    bigPDFPages: 50,
    pdfStep: 30,
    docStep: 40,
    maxPages: 5000,
    maxChunks: 5000,
    minPageText: 200,
    photoBudget: 140,
    screenshotBudget: 280,
    pageBudget: 280,
    frameBudget: 70,
    frameInterval: 3.0,
    audioWindow: 30.0,
    audioHop: 25.0,
    excludedFolders: [],
    excludedExtensions: new Set<string>()
  };
}

export function parseExtensions(text: string): Set<string> {
  const parts = text.split(/[\s,]+/);
  const exts = new Set<string>();
  for (const part of parts) {
    const cleaned = part.replace(/^[*.]+/, '').toLowerCase().trim();
    if (cleaned) exts.add(cleaned);
  }
  return exts;
}

export function indexFingerprint(options: IndexOptions): string {
  if (options.code) {
    return `code1 lines${options.codeStretch}`;
  }
  return `photo${options.photoBudget} shot${options.screenshotBudget} page${options.pageBudget} frame${options.frameBudget}/${options.frameInterval}s audio${options.audioWindow}/${options.audioHop}s`;
}

export interface SearchHit {
  path: string;
  kind: FileKind;
  score: number;
  z?: number | null;
  cosine?: number | null;
  keywordRank?: number | null;
  segment: SegmentKind;
  segmentID: number;
  loc?: number | null;
  locEnd?: number | null;
  excerpt?: string | null;
  modified: number;
  size: number;
  info: Record<string, string>;
  focus?: number | null;
}

export interface WorkerInfo {
  model: string;
  revision: string;
  runtime: string;
  dtype: string;
  dim: number;
  textOnly: boolean;
  loadSeconds: number | null;
}

export interface EmbeddingResult {
  vector: number[] | null;
  error: string | null;
}

export const Prompts = {
  query: (text: string) => `task: search result | query: ${text}`,
  codeQuery: (text: string) => `task: code retrieval | query: ${text}`,
  document: (title: string | null, text: string) => 
    `title: ${(!title || title.length === 0) ? 'none' : title} | text: ${text}`
};

export const CodeQuery = {
  prefix: 'code:',
  text: (query: string): string | null => {
    const trimmed = query.trimStart();
    if (trimmed.toLowerCase().startsWith('code:')) {
      return trimmed.slice(5).trim();
    }
    return null;
  }
};
