package com.digup.extractors;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Audio and video perceptual hashing and media piece preparation.
 */
public final class MediaExtractors {

    public static class MediaPiece {
        public final String path;
        public final double start;
        public double end;

        public MediaPiece(String path, double start, double end) {
            this.path = path;
            this.start = start;
            this.end = end;
        }
    }

    private MediaExtractors() {}

    /**
     * 8x8 grayscale perceptual average hash for deduplicating adjacent video frames.
     */
    public static long averageHash(BufferedImage image) {
        BufferedImage small = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, 0, 0, 8, 8, null);
        g.dispose();

        int sum = 0;
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int gray = small.getRaster().getSample(x, y, 0);
                pixels[y * 8 + x] = gray;
                sum += gray;
            }
        }
        int mean = sum / 64;

        long hash = 0L;
        for (int i = 0; i < 64; i++) {
            if (pixels[i] > mean) {
                hash |= (1L << i);
            }
        }
        return hash;
    }

    public static double rms(short[] samples) {
        if (samples.length == 0) return 0.0;
        double sumSq = 0.0;
        for (short s : samples) {
            double v = (double) s / 32768.0;
            sumSq += v * v;
        }
        return Math.sqrt(sumSq / samples.length);
    }

    public static List<MediaPiece> extractVideoKeyframes(File videoFile, double intervalSeconds, int maxFrames, File tempFolder) {
        List<MediaPiece> pieces = new ArrayList<>();
        // Use ffmpeg if available on PATH
        try {
            File outPattern = new File(tempFolder, "frame_%04d.jpg");
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", videoFile.getAbsolutePath(),
                "-vf", "fps=1/" + intervalSeconds + ",scale='min(720,iw)':-2",
                "-vframes", String.valueOf(maxFrames),
                outPattern.getAbsolutePath()
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor();

            File[] files = tempFolder.listFiles((dir, name) -> name.startsWith("frame_") && name.endsWith(".jpg"));
            if (files != null && files.length > 0) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                Long lastHash = null;
                double curTime = 0.0;

                for (File f : files) {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null) {
                        long h = averageHash(img);
                        if (lastHash != null && Long.bitCount(h ^ lastHash) <= 4) {
                            f.delete();
                        } else {
                            lastHash = h;
                            pieces.add(new MediaPiece(f.getAbsolutePath(), curTime, curTime + intervalSeconds));
                        }
                    }
                    curTime += intervalSeconds;
                }
            }
        } catch (Exception ignored) {}
        return pieces;
    }
}
