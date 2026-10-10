import fs from 'fs';
import path from 'path';
import { spawn } from 'child_process';

export interface MediaPiece {
  path: string;
  start: number;
  end: number;
}

export class MediaExtractors {
  /**
   * 8x8 average hash for deduplicating video frames
   */
  public static averageHash(pixelBuffer: Buffer, width: number, height: number): bigint {
    // Grayscale downsample to 8x8
    const side = 8;
    const gray = new Uint8Array(side * side);
    let sum = 0;

    for (let y = 0; y < side; y++) {
      for (let x = 0; x < side; x++) {
        const srcX = Math.floor((x / side) * width);
        const srcY = Math.floor((y / side) * height);
        const idx = (srcY * width + srcX) * 3; // RGB
        const val = Math.floor((pixelBuffer[idx] + pixelBuffer[idx + 1] + pixelBuffer[idx + 2]) / 3);
        gray[y * side + x] = val;
        sum += val;
      }
    }

    const mean = sum / (side * side);
    let hash = 0n;

    for (let i = 0; i < side * side; i++) {
      if (gray[i] > mean) {
        hash |= 1n << BigInt(i);
      }
    }

    return hash;
  }

  public static hammingDistance(a: bigint, b: bigint): number {
    let diff = a ^ b;
    let count = 0;
    while (diff > 0n) {
      if (diff & 1n) count++;
      diff >>= 1n;
    }
    return count;
  }

  /**
   * Compute RMS for audio slice (16-bit PCM)
   */
  public static rms(samples: Int16Array): number {
    if (samples.length === 0) return 0;
    let sum = 0;
    for (let i = 0; i < samples.length; i++) {
      const val = samples[i] / 32768;
      sum += val * val;
    }
    return Math.sqrt(sum / samples.length);
  }
}
