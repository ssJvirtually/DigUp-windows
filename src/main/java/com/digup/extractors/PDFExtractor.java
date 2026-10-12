package com.digup.extractors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PDF text extraction and page rendering for OCR and vision embeddings.
 */
public final class PDFExtractor {

    public static class Page {
        public final int number; // 1-based
        public final String text;
        public final String imagePath;

        public Page(int number, String text, String imagePath) {
            this.number = number;
            this.text = text;
            this.imagePath = imagePath;
        }
    }

    public static class PDFResult {
        public final List<Page> pages;
        public final int totalPages;
        public final String title;

        public PDFResult(List<Page> pages, int totalPages, String title) {
            this.pages = pages;
            this.totalPages = totalPages;
            this.title = title;
        }
    }

    public static PDFResult extract(File file, int startPage, int maxPagesToRead, int minText, File tempFolder) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            if (document.isEncrypted()) {
                throw new IOException("PDF is password protected");
            }

            int total = document.getNumberOfPages();
            String title = (document.getDocumentInformation() != null)
                ? document.getDocumentInformation().getTitle()
                : null;

            List<Page> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);

            int endPage = Math.min(startPage + maxPagesToRead - 1, total);

            for (int p = startPage; p <= endPage; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(document).trim();

                String imgPath = null;
                if (pageText.length() < minText) {
                    try {
                        BufferedImage bim = renderer.renderImageWithDPI(p - 1, 150);
                        File out = new File(tempFolder, UUID.randomUUID() + ".png");
                        ImageIO.write(bim, "PNG", out);
                        imgPath = out.getAbsolutePath();
                    } catch (Exception ignored) {}
                }

                pages.add(new Page(p, pageText, imgPath));
            }

            return new PDFResult(pages, total, title);
        }
    }
}
