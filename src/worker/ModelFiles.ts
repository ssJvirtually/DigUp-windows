import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import https from 'https';
import http from 'http';
import {
  MODEL_REPO,
  MODEL_REVISION,
  TEXT_MODEL_FILE,
  MEDIA_MODEL_FILE,
  TOTAL_MODEL_BYTES,
  getModelsDir
} from '../shared/constants.js';

export interface ModelDownloadProgress {
  file: string;
  fileIndex: number;
  totalFiles: number;
  downloadedBytes: number;
  totalBytes: number;
  percent: number;
}

export class ModelFiles {
  public static readonly text = TEXT_MODEL_FILE;
  public static readonly media = MEDIA_MODEL_FILE;
  public static readonly all = [TEXT_MODEL_FILE, MEDIA_MODEL_FILE];
  public static readonly totalBytes = TOTAL_MODEL_BYTES;

  public static get installedDir(): string {
    const dir = getModelsDir();
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    return dir;
  }

  public static isModelReady(): boolean {
    const dir = this.installedDir;
    for (const file of this.all) {
      const p = path.join(dir, file.name);
      if (!fs.existsSync(p)) return false;
      const stat = fs.statSync(p);
      if (stat.size !== file.bytes) return false;
    }
    return true;
  }

  public static isTextModelReady(): boolean {
    const p = path.join(this.installedDir, this.text.name);
    if (!fs.existsSync(p)) return false;
    return fs.statSync(p).size === this.text.bytes;
  }

  public static getModelPath(name: string): string {
    return path.join(this.installedDir, name);
  }

  public static async downloadAll(
    onProgress?: (p: ModelDownloadProgress) => void
  ): Promise<void> {
    const dir = this.installedDir;
    let overallDownloaded = 0;

    for (let i = 0; i < this.all.length; i++) {
      const fileInfo = this.all[i];
      const targetPath = path.join(dir, fileInfo.name);

      if (fs.existsSync(targetPath) && fs.statSync(targetPath).size === fileInfo.bytes) {
        overallDownloaded += fileInfo.bytes;
        if (onProgress) {
          onProgress({
            file: fileInfo.name,
            fileIndex: i + 1,
            totalFiles: this.all.length,
            downloadedBytes: overallDownloaded,
            totalBytes: this.totalBytes,
            percent: Math.round((overallDownloaded / this.totalBytes) * 100)
          });
        }
        continue;
      }

      const tempPath = targetPath + '.download';
      const url = `https://huggingface.co/${MODEL_REPO}/resolve/${MODEL_REVISION}/${fileInfo.name}`;

      await this.downloadFile(url, tempPath, (fileBytes) => {
        if (onProgress) {
          const currentTotal = overallDownloaded + fileBytes;
          onProgress({
            file: fileInfo.name,
            fileIndex: i + 1,
            totalFiles: this.all.length,
            downloadedBytes: currentTotal,
            totalBytes: this.totalBytes,
            percent: Math.min(100, Math.round((currentTotal / this.totalBytes) * 100))
          });
        }
      });

      // Verify SHA256
      const hash = await this.sha256(tempPath);
      if (hash.toLowerCase() !== fileInfo.sha256.toLowerCase()) {
        fs.unlinkSync(tempPath);
        throw new Error(`Checksum mismatch for ${fileInfo.name}: expected ${fileInfo.sha256}, got ${hash}`);
      }

      fs.renameSync(tempPath, targetPath);
      overallDownloaded += fileInfo.bytes;
    }
  }

  private static downloadFile(
    url: string,
    destPath: string,
    onProgress: (bytes: number) => void
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const fileStream = fs.createWriteStream(destPath);
      let downloaded = 0;

      const request = (targetUrl: string) => {
        const client = targetUrl.startsWith('https') ? https : http;
        client
          .get(targetUrl, { headers: { 'User-Agent': 'DigUp-Windows/1.0' } }, (res) => {
            // Handle HTTP redirects (Hugging Face redirects to CDN)
            if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
              request(res.headers.location);
              return;
            }

            if (res.statusCode !== 200) {
              fileStream.close();
              reject(new Error(`Failed to download ${targetUrl}, status code: ${res.statusCode}`));
              return;
            }

            res.on('data', (chunk) => {
              downloaded += chunk.length;
              onProgress(downloaded);
            });

            res.pipe(fileStream);

            fileStream.on('finish', () => {
              fileStream.close();
              resolve();
            });
          })
          .on('error', (err) => {
            fileStream.close();
            if (fs.existsSync(destPath)) fs.unlinkSync(destPath);
            reject(err);
          });
      };

      request(url);
    });
  }

  public static sha256(filePath: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const hash = crypto.createHash('sha256');
      const stream = fs.createReadStream(filePath);
      stream.on('data', (data) => hash.update(data));
      stream.on('end', () => resolve(hash.digest('hex')));
      stream.on('error', (err) => reject(err));
    });
  }
}
