package com.digup.extractors;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Image processing, downscaling, and thumbnail preparation for embedding worker.
 */
public final class ImageExtractor {

    public static class PreparedImage {
        public final String path;
        public final int width;
        public final int height;
        public final String taken;

        public PreparedImage(String path, int width, int height, String taken) {
            this.path = path;
            this.width = width;
            this.height = height;
            this.taken = taken;
        }
    }

    private ImageExtractor() {}

    public static PreparedImage prepare(File file, int maxPixel, int minSide, File tempFolder, boolean png) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("Cannot decode image: " + file.getName());
        }

        int w = img.getWidth();
        int h = img.getHeight();

        if (Math.max(w, h) < minSide) {
            throw new IOException("Image too small: " + w + "x" + h);
        }

        double scale = 1.0;
        if (Math.max(w, h) > maxPixel) {
            scale = (double) maxPixel / Math.max(w, h);
        }

        int targetW = (int) Math.round(w * scale);
        int targetH = (int) Math.round(h * scale);

        BufferedImage resized = new BufferedImage(targetW, targetH, png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (!png) {
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, targetW, targetH);
        }
        g2d.drawImage(img, 0, 0, targetW, targetH, null);
        g2d.dispose();

        File out = new File(tempFolder, UUID.randomUUID() + (png ? ".png" : ".jpg"));
        ImageIO.write(resized, png ? "PNG" : "JPEG", out);

        return new PreparedImage(out.getAbsolutePath(), w, h, null);
    }
}
