import { spawn, ChildProcess } from 'child_process';
import readline from 'readline';
import { WorkerInfo, EmbeddingResult } from '../shared/types.js';
import { DIMENSION } from '../shared/constants.js';

export class WorkerClient {
  private process: ChildProcess | null = null;
  private pendingRequests = new Map<
    number,
    {
      resolve: (value: any) => void;
      reject: (err: any) => void;
    }
  >();
  private nextId = 1;
  public info: WorkerInfo | null = null;
  public isReady = false;
  private idleTimer: NodeJS.Timeout | null = null;
  private idleTimeoutMs: number;

  constructor(idleTimeoutMs = 30000) {
    this.idleTimeoutMs = idleTimeoutMs;
  }

  public async start(workerPath: string, args: string[] = []): Promise<WorkerInfo> {
    if (this.isReady && this.info) {
      this.resetIdleTimer();
      return this.info;
    }

    return new Promise((resolve, reject) => {
      try {
        const proc = spawn('python', [workerPath, ...args], {
          stdio: ['pipe', 'pipe', 'inherit'],
          env: {
            ...process.env,
            PYTHONUNBUFFERED: '1',
            TOKENIZERS_PARALLELISM: 'false'
          }
        });

        this.process = proc;

        const rl = readline.createInterface({
          input: proc.stdout!,
          crlfDelay: Infinity
        });

        let initialHandshakeDone = false;

        rl.on('line', (line) => {
          if (!line.trim()) return;
          try {
            const data = JSON.parse(line);

            if (!initialHandshakeDone && 'ready' in data) {
              initialHandshakeDone = true;
              if (data.ready) {
                this.isReady = true;
                this.info = data.info;
                this.resetIdleTimer();
                resolve(this.info!);
              } else {
                reject(new Error(data.error || 'Worker failed to ready'));
              }
              return;
            }

            if ('id' in data) {
              const pending = this.pendingRequests.get(data.id);
              if (pending) {
                this.pendingRequests.delete(data.id);
                if (data.ok) {
                  pending.resolve(data);
                } else {
                  pending.reject(new Error(data.error || 'Worker request failed'));
                }
              }
            }
          } catch (err) {
            console.error('Worker JSON parse error:', err, 'Line:', line);
          }
        });

        proc.on('error', (err) => {
          this.isReady = false;
          if (!initialHandshakeDone) reject(err);
        });

        proc.on('exit', () => {
          this.isReady = false;
          this.process = null;
        });
      } catch (err) {
        reject(err);
      }
    });
  }

  public async embedTexts(texts: string[]): Promise<EmbeddingResult[]> {
    this.resetIdleTimer();
    const res = await this.sendRequest({
      op: 'text',
      inputs: texts
    });
    return this.parseVectorsResponse(res, texts.length);
  }

  public async embedImages(imagePaths: string[], budget = 280): Promise<EmbeddingResult[]> {
    this.resetIdleTimer();
    const res = await this.sendRequest({
      op: 'image',
      inputs: imagePaths,
      budget
    });
    return this.parseVectorsResponse(res, imagePaths.length);
  }

  public async embedAudio(audioPaths: string[]): Promise<EmbeddingResult[]> {
    this.resetIdleTimer();
    const res = await this.sendRequest({
      op: 'audio',
      inputs: audioPaths
    });
    return this.parseVectorsResponse(res, audioPaths.length);
  }

  private sendRequest(payload: Record<string, any>): Promise<any> {
    if (!this.process || !this.process.stdin || !this.isReady) {
      return Promise.reject(new Error('Worker is not running'));
    }

    const id = this.nextId++;
    const req = { id, ...payload };

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(id, { resolve, reject });
      this.process!.stdin!.write(JSON.stringify(req) + '\n');
    });
  }

  private parseVectorsResponse(res: any, count: number): EmbeddingResult[] {
    const dim = res.dim || DIMENSION;
    const base64Str = res.vectors || '';
    const buffer = Buffer.from(base64Str, 'base64');
    const floatArray = new Float32Array(
      buffer.buffer,
      buffer.byteOffset,
      Math.floor(buffer.byteLength / 4)
    );

    const results: EmbeddingResult[] = [];
    const errors = res.errors || [];

    for (let i = 0; i < count; i++) {
      const err = errors[i] || null;
      if (err) {
        results.push({ vector: null, error: String(err) });
      } else {
        const slice = floatArray.slice(i * dim, (i + 1) * dim);
        results.push({ vector: Array.from(slice), error: null });
      }
    }

    return results;
  }

  private resetIdleTimer(): void {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
    if (this.idleTimeoutMs > 0) {
      this.idleTimer = setTimeout(() => {
        this.close();
      }, this.idleTimeoutMs);
    }
  }

  public close(): void {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
    if (this.process && this.process.stdin) {
      try {
        this.process.stdin.write(JSON.stringify({ op: 'quit' }) + '\n');
      } catch {}
      setTimeout(() => {
        if (this.process) {
          try {
            this.process.kill();
          } catch {}
          this.process = null;
        }
      }, 500);
    }
    this.isReady = false;
  }
}
