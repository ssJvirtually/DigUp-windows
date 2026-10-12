package com.digup.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Operations on 768-dimensional float32 and float16 embeddings.
 */
public final class Vectors {
    public static final int DIMENSION = 768;
    public static final int BYTES_PER_VEC = DIMENSION * 2; // 1536 bytes in float16

    private Vectors() {}

    /**
     * Converts a float32 array into little-endian float16 bytes.
     */
    public static byte[] half(float[] vector) {
        if (vector == null) return null;
        byte[] bytes = new byte[vector.length * 2];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            short h = Float.floatToFloat16(v);
            buf.putShort(h);
        }
        return bytes;
    }

    /**
     * Converts little-endian float16 bytes into a float32 array.
     */
    public static float[] floats(byte[] bytes) {
        if (bytes == null) return null;
        int count = bytes.length / 2;
        float[] out = new float[count];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            short h = buf.getShort();
            out[i] = Float.float16ToFloat(h);
        }
        return out;
    }

    /**
     * Converts a contiguous slice of float16 shorts into float32 array in place.
     */
    public static void toFloats(short[] source, int srcOffset, float[] destination, int destOffset, int count) {
        for (int i = 0; i < count; i++) {
            destination[destOffset + i] = Float.float16ToFloat(source[srcOffset + i]);
        }
    }

    /**
     * Dot product between two vectors (for L2-normalized vectors this is cosine similarity).
     */
    public static float dot(float[] a, float[] b) {
        float sum = 0f;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Dot product between float32 query and float16 short vector slice.
     */
    public static float dotHalf(float[] query, short[] halves, int halfOffset) {
        float sum = 0f;
        for (int i = 0; i < DIMENSION; i++) {
            sum += query[i] * Float.float16ToFloat(halves[halfOffset + i]);
        }
        return sum;
    }

    /**
     * In-place L2 normalization.
     */
    public static void normalize(float[] v) {
        double sumSq = 0.0;
        for (float f : v) {
            sumSq += f * f;
        }
        double norm = Math.sqrt(sumSq);
        if (norm > 1e-9) {
            float factor = (float) (1.0 / norm);
            for (int i = 0; i < v.length; i++) {
                v[i] *= factor;
            }
        }
    }
}
