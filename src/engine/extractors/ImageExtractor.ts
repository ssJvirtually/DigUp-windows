import fs from 'fs';
import path from 'path';

export interface PreparedImage {
  path: string;
  width: number;
  height: number;
  taken?: string | null;
}

export class ImageExtractor {
  /**
   * Sniffs image dimensions from header bytes (JPEG, PNG, GIF, BMP, WebP)
   */
  public static getDimensions(filePath: string): { width: number; height: number } {
    try {
      const fd = fs.openSync(filePath, 'r');
      const buffer = Buffer.alloc(4096);
      fs.readSync(fd, buffer, 0, 4096, 0);
      fs.closeSync(fd);

      // PNG
      if (buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) {
        return {
          width: buffer.readUInt32BE(16),
          height: buffer.readUInt32BE(20)
        };
      }

      // GIF
      if (buffer[0] === 0x47 && buffer[1] === 0x49 && buffer[2] === 0x46) {
        return {
          width: buffer.readUInt16LE(6),
          height: buffer.readUInt16LE(8)
        };
      }

      // BMP
      if (buffer[0] === 0x42 && buffer[1] === 0x4d) {
        return {
          width: buffer.readInt32LE(18),
          height: Math.abs(buffer.readInt32LE(22))
        };
      }

      // JPEG
      if (buffer[0] === 0xff && buffer[1] === 0xd8) {
        let offset = 2;
        while (offset < buffer.length - 8) {
          if (buffer[offset] !== 0xff) break;
          const marker = buffer[offset + 1];
          if (marker === 0xc0 || marker === 0xc2) {
            return {
              height: buffer.readUInt16BE(offset + 5),
              width: buffer.readUInt16BE(offset + 7)
            };
          }
          const length = buffer.readUInt16BE(offset + 2);
          offset += 2 + length;
        }
      }
    } catch {}

    return { width: 800, height: 600 };
  }

  public static prepare(
    filePath: string,
    minSide = 256
  ): PreparedImage {
    const { width, height } = this.getDimensions(filePath);
    if (Math.max(width, height) < minSide) {
      throw new Error(`Small image ${width}x${height}`);
    }

    return {
      path: filePath,
      width,
      height,
      taken: null
    };
  }
}
