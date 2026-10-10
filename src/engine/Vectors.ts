import { DIMENSION } from '../shared/constants.js';

// Float16 (IEEE 754-2008 half-precision) conversion utilities
export class Vectors {
  public static readonly dimension = DIMENSION;

  /**
   * Converts Float32Array to Float16 byte Buffer (768 * 2 = 1536 bytes)
   */
  public static toHalf(floats: number[] | Float32Array): Buffer {
    const buffer = Buffer.alloc(floats.length * 2);
    for (let i = 0; i < floats.length; i++) {
      const h = this.float32ToFloat16(floats[i]);
      buffer.writeUInt16LE(h, i * 2);
    }
    return buffer;
  }

  /**
   * Converts Float16 byte Buffer to Float32Array
   */
  public static fromHalf(buffer: Buffer): Float32Array {
    const count = Math.floor(buffer.length / 2);
    const floats = new Float32Array(count);
    for (let i = 0; i < count; i++) {
      const h = buffer.readUInt16LE(i * 2);
      floats[i] = this.float16ToFloat32(h);
    }
    return floats;
  }

  /**
   * Computes L2-normalized vector
   */
  public static normalize(vec: number[] | Float32Array): Float32Array {
    const out = new Float32Array(vec.length);
    let sum = 0;
    for (let i = 0; i < vec.length; i++) {
      sum += vec[i] * vec[i];
    }
    const scale = sum > 0 ? 1 / Math.sqrt(sum) : 0;
    for (let i = 0; i < vec.length; i++) {
      out[i] = vec[i] * scale;
    }
    return out;
  }

  /**
   * Dot product between two normalized vectors (cosine similarity)
   */
  public static dot(a: Float32Array, b: Float32Array): number {
    let sum = 0;
    const len = a.length;
    for (let i = 0; i < len; i++) {
      sum += a[i] * b[i];
    }
    return sum;
  }

  // IEEE 754 float32 to float16
  private static float32ToFloat16(val: number): number {
    const f32 = new Float32Array(1);
    const u32 = new Uint32Array(f32.buffer);
    f32[0] = val;
    const x = u32[0];

    const sign = (x >> 31) & 0x1;
    let exp = (x >> 23) & 0xff;
    let mant = x & 0x7fffff;

    if (exp === 0xff) {
      // NaN or Infinity
      return (sign << 15) | 0x7c00 | (mant !== 0 ? 0x0200 : 0);
    }

    exp = exp - 127 + 15;

    if (exp >= 31) {
      // Overflow to infinity
      return (sign << 15) | 0x7c00;
    }

    if (exp <= 0) {
      // Subnormal or zero
      if (exp < -10) return sign << 15;
      mant = (mant | 0x800000) >> (1 - exp);
      return (sign << 15) | (mant >> 13);
    }

    return (sign << 15) | (exp << 10) | (mant >> 13);
  }

  // IEEE 754 float16 to float32
  private static float16ToFloat32(h: number): number {
    const sign = (h >> 15) & 0x1;
    const exp = (h >> 10) & 0x1f;
    const mant = h & 0x3ff;

    let fExp: number;
    let fMant: number;

    if (exp === 0) {
      if (mant === 0) {
        return sign ? -0 : 0;
      }
      // Subnormal
      let m = mant;
      let shift = 0;
      while ((m & 0x400) === 0) {
        m <<= 1;
        shift++;
      }
      fExp = 127 - 15 - shift + 1;
      fMant = (m & 0x3ff) << 13;
    } else if (exp === 31) {
      fExp = 255;
      fMant = mant ? 0x200000 : 0;
    } else {
      fExp = exp - 15 + 127;
      fMant = mant << 13;
    }

    const u32 = new Uint32Array(1);
    u32[0] = (sign << 31) | (fExp << 23) | fMant;
    const f32 = new Float32Array(u32.buffer);
    return f32[0];
  }
}
