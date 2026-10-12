package com.digup.engine;

import com.digup.extractors.*;
import com.digup.model.*;
import com.digup.worker.WorkerClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pipeline processor extracting text/media and computing embeddings for pending files.
 */
public final class Indexer {

    private final IndexStore store;
    private final WorkerClient worker;
    private final IndexOptions options;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public interface ProgressListener {
        void onProgress(int pending, int total, String currentFile);
    }

    private ProgressListener listener;

    public Indexer(IndexStore store, WorkerClient worker, IndexOptions options) {
        this.store = store;
        this.worker = worker;
        this.options = options;
    }

    public void setListener(ProgressListener listener) {
        this.listener = listener;
    }

    public void setPaused(boolean p) {
        this.paused.set(p);
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void stop() {
        running.set(false);
    }

    public void runLoop() {
        running.set(true);
        File tempDir = AppSettings.getAppDataDir().resolve("temp").toFile();
        if (!tempDir.exists()) tempDir.mkdirs();

        while (running.get()) {
            if (paused.get()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                continue;
            }

            try {
                List<FileRecord> pending = store.pending(20, null);
                if (pending.isEmpty()) {
                    // Check unfinished long files
                    List<FileRecord> unfinished = store.unfinished();
                    if (unfinished.isEmpty()) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    pending = unfinished.subList(0, Math.min(5, unfinished.size()));
                }

                int totalCount = store.pendingCount();
                for (FileRecord rec : pending) {
                    if (!running.get() || paused.get()) break;
                    if (listener != null) {
                        listener.onProgress(totalCount, totalCount, rec.getPath());
                    }

                    indexSingleFile(rec, tempDir);
                }
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void indexSingleFile(FileRecord rec, File tempDir) {
        File file = new File(rec.getPath());
        if (!file.exists()) {
            try {
                store.delete(rec.getId());
            } catch (Exception ignored) {}
            return;
        }

        try {
            switch (rec.getKind()) {
                case screenshot:
                case image:
                    indexImage(rec, file, tempDir);
                    break;
                case pdf:
                    indexPdf(rec, file, tempDir);
                    break;
                case doc:
                    indexDoc(rec, file);
                    break;
                case code:
                    indexCode(rec, file);
                    break;
                case audio:
                case video:
                    indexMedia(rec, file, tempDir);
                    break;
            }
        } catch (Exception e) {
            try {
                store.finish(rec.getId(), "failed", e.getMessage(), Map.of());
            } catch (Exception ignored) {}
        }
    }

    private void indexImage(FileRecord rec, File file, File tempDir) throws Exception {
        ImageExtractor.PreparedImage prep = ImageExtractor.prepare(file, options.imagePixels, options.minSide, tempDir, false);
        float[][] vecs = worker.embedImages(List.of(prep.path), 280);
        float[] v = (vecs.length > 0) ? vecs[0] : null;

        String ocrText = OCRExtractor.recognizeText(file);
        store.addSegment(rec.getId(), SegmentKind.full, Modality.image, null, null, null, ocrText, v);

        Map<String, String> info = new HashMap<>();
        info.put("width", String.valueOf(prep.width));
        info.put("height", String.valueOf(prep.height));
        store.finish(rec.getId(), "done", null, info);

        new File(prep.path).delete();
    }

    private void indexPdf(FileRecord rec, File file, File tempDir) throws Exception {
        int last = store.lastPlace(rec.getId()) != null ? store.lastPlace(rec.getId()) : 0;
        int startPage = last + 1;

        PDFExtractor.PDFResult res = PDFExtractor.extract(file, startPage, options.pdfStep, options.minText, tempDir);

        List<String> textsToEmbed = new ArrayList<>();
        List<PDFExtractor.Page> textPages = new ArrayList<>();
        List<String> imagesToEmbed = new ArrayList<>();
        List<PDFExtractor.Page> imagePages = new ArrayList<>();

        for (PDFExtractor.Page p : res.pages) {
            if (p.imagePath != null) {
                imagesToEmbed.add(p.imagePath);
                imagePages.add(p);
            } else if (SearchText.hasWords(p.text)) {
                textsToEmbed.add("task: search result | query: " + p.text);
                textPages.add(p);
            } else {
                store.addSegment(rec.getId(), SegmentKind.page, Modality.none, (double) p.number, null, p.text, p.text, null);
            }
        }

        if (!textsToEmbed.isEmpty()) {
            float[][] vecs = worker.embedTexts(textsToEmbed);
            for (int i = 0; i < textPages.size(); i++) {
                PDFExtractor.Page p = textPages.get(i);
                float[] vec = (i < vecs.length) ? vecs[i] : null;
                store.addSegment(rec.getId(), SegmentKind.page, Modality.text, (double) p.number, null, p.text, p.text, vec);
            }
        }

        if (!imagesToEmbed.isEmpty()) {
            float[][] vecs = worker.embedImages(imagesToEmbed, 280);
            for (int i = 0; i < imagePages.size(); i++) {
                PDFExtractor.Page p = imagePages.get(i);
                float[] vec = (i < vecs.length) ? vecs[i] : null;
                store.addSegment(rec.getId(), SegmentKind.page_image, Modality.image, (double) p.number, null, null, null, vec);
                new File(p.imagePath).delete();
            }
        }

        boolean finished = (startPage + res.pages.size() - 1 >= res.totalPages);
        Map<String, String> info = new HashMap<>();
        info.put("pages", String.valueOf(res.totalPages));
        if (res.title != null) info.put("title", res.title);
        info.put("read_to", String.valueOf(startPage + res.pages.size() - 1));
        info.put("read_end", String.valueOf(res.totalPages));

        store.finish(rec.getId(), finished ? "done" : "partial", finished ? null : "read to page " + (startPage + res.pages.size() - 1), info);
    }

    private void indexDoc(FileRecord rec, File file) throws Exception {
        String text = DocExtractor.extractText(file);
        List<String> chunks = DocExtractor.chunks(text, options.docChunkSize, options.docChunkOverlap, options.maxChunks);

        int last = store.lastPlace(rec.getId()) != null ? store.lastPlace(rec.getId()) : 0;
        int startIdx = last;
        int endIdx = Math.min(startIdx + options.docStep, chunks.size());

        List<String> toEmbed = new ArrayList<>();
        List<String> slice = chunks.subList(startIdx, endIdx);

        for (String c : slice) {
            if (SearchText.hasWords(c)) {
                toEmbed.add("task: search result | query: " + c);
            }
        }

        float[][] vecs = !toEmbed.isEmpty() ? worker.embedTexts(toEmbed) : new float[0][0];
        int vecIdx = 0;

        for (int i = 0; i < slice.size(); i++) {
            String chunk = slice.get(i);
            int chunkNum = startIdx + i + 1;
            float[] vec = null;
            Modality mod = Modality.none;

            if (SearchText.hasWords(chunk) && vecIdx < vecs.length) {
                vec = vecs[vecIdx++];
                mod = Modality.text;
            }

            store.addSegment(rec.getId(), SegmentKind.chunk, mod, (double) chunkNum, null, chunk, chunk, vec);
        }

        boolean finished = (endIdx >= chunks.size());
        Map<String, String> info = new HashMap<>();
        info.put("chunks", String.valueOf(chunks.size()));
        info.put("chars", String.valueOf(text.length()));
        info.put("read_to", String.valueOf(endIdx));
        info.put("read_end", String.valueOf(chunks.size()));

        store.finish(rec.getId(), finished ? "done" : "partial", finished ? null : "read to chunk " + endIdx, info);
    }

    private void indexCode(FileRecord rec, File file) throws Exception {
        CodeExtractor.CodeFile cf = CodeExtractor.read(file, options);
        List<String> prompts = new ArrayList<>();
        for (CodeExtractor.Piece p : cf.pieces) {
            prompts.add("task: search result | query: " + cf.title + "\n" + p.text);
        }

        float[][] vecs = worker.embedTexts(prompts);
        for (int i = 0; i < cf.pieces.size(); i++) {
            CodeExtractor.Piece p = cf.pieces.get(i);
            float[] v = (i < vecs.length) ? vecs[i] : null;
            store.addSegment(rec.getId(), SegmentKind.lines, Modality.text, (double) p.first, (double) p.last, p.text, p.text, v);
        }

        Map<String, String> info = new HashMap<>();
        info.put("lines", String.valueOf(cf.lines));
        if (cf.cells != null) info.put("cells", String.valueOf(cf.cells));
        if (cf.repo != null) info.put("repo", cf.repo);

        store.finish(rec.getId(), "done", null, info);
    }

    private void indexMedia(FileRecord rec, File file, File tempDir) throws Exception {
        if (rec.getKind() == FileKind.video) {
            List<MediaExtractors.MediaPiece> pieces = MediaExtractors.extractVideoKeyframes(file, 3.0, 100, tempDir);
            if (!pieces.isEmpty()) {
                List<String> paths = pieces.stream().map(p -> p.path).toList();
                float[][] vecs = worker.embedImages(paths, 280);
                for (int i = 0; i < pieces.size(); i++) {
                    MediaExtractors.MediaPiece p = pieces.get(i);
                    float[] v = (i < vecs.length) ? vecs[i] : null;
                    store.addSegment(rec.getId(), SegmentKind.moment, Modality.image, p.start, p.end, null, null, v);
                    new File(p.path).delete();
                }
            }
        }
        store.finish(rec.getId(), "done", null, Map.of());
    }
}
