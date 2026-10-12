package com.digup.engine;

import com.digup.model.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Hybrid semantic and keyword search engine with 100% mathematical parity to macOS DigUp.
 */
public final class Searcher {

    public static final double WORD_WEIGHT = 2.0;
    public static final double CODE_WORD_WEIGHT = 1.0;

    private final IndexStore store;
    private final Gson gson = new Gson();

    private short[] matrixHalves = null; // rows * 768 float16 shorts
    private final List<Long> rowFile = new ArrayList<>();
    private final List<Long> rowSegment = new ArrayList<>();
    private final List<Modality> rowModality = new ArrayList<>();
    private final List<Boolean> rowSetsScale = new ArrayList<>();
    private final Map<Long, FileKind> fileKind = new HashMap<>();

    private String vectorSource = null;
    private boolean vectorsLoaded = false;

    public Searcher(IndexStore store, boolean loadVectors) throws Exception {
        this.store = store;
        if (loadVectors) {
            reload();
        } else {
            reloadFiles();
        }
    }

    public boolean isCode() {
        return store.getFile().getFileName().toString().equals(IndexStore.CODE_FILE);
    }

    public int getVectorCount() {
        return rowFile.size();
    }

    public String getVectorSource() {
        return vectorSource;
    }

    public synchronized void reload() throws Exception {
        releaseVectors();
        reloadFiles();

        int[] rowCount = new int[]{0};
        store.getDatabase().query("SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL", rs -> rowCount[0] = rs.getInt(1));
        if (rowCount[0] == 0) {
            vectorsLoaded = true;
            return;
        }

        int totalHalves = rowCount[0] * Vectors.DIMENSION;
        matrixHalves = new short[totalHalves];

        int bytesPerRow = Vectors.BYTES_PER_VEC;
        IndexOptions options = new IndexOptions();

        store.getDatabase().query("""
            SELECT s.id, s.file, s.modality, s.vec, s.kind, s.loc, f.kind, CAST(json_extract(f.info, '$.pages') AS INTEGER)
            FROM segments s JOIN files f ON f.id = s.file WHERE s.vec IS NOT NULL ORDER BY s.id
            """, rs -> {
            byte[] blob = rs.getBytes(4);
            if (blob == null || blob.length != bytesPerRow || rowFile.size() >= rowCount[0]) return;

            int rowIdx = rowFile.size();
            ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
            int offset = rowIdx * Vectors.DIMENSION;
            for (int i = 0; i < Vectors.DIMENSION; i++) {
                matrixHalves[offset + i] = buf.getShort();
            }

            rowSegment.add(rs.getLong(1));
            rowFile.add(rs.getLong(2));
            rowModality.add(Modality.fromRaw(rs.getString(3)));

            String kindStr = rs.getString(5);
            double locVal = rs.getDouble(6);
            int place = rs.wasNull() ? 0 : (int) locVal;
            String fileKindStr = rs.getString(7);
            int pages = rs.getInt(8);

            boolean later = "pdf".equals(fileKindStr)
                ? !SegmentKind.chunk.getRaw().equals(kindStr) && pages > options.bigPDFPages && place > options.pdfStep
                : SegmentKind.chunk.getRaw().equals(kindStr) && place > options.docStep;

            rowSetsScale.add(!later);
        });

        vectorsLoaded = true;
    }

    public synchronized void releaseVectors() {
        matrixHalves = null;
        rowFile.clear();
        rowSegment.clear();
        rowModality.clear();
        rowSetsScale.clear();
        vectorsLoaded = false;
    }

    private void reloadFiles() throws SQLException {
        fileKind.clear();
        store.getDatabase().query("SELECT id, kind FROM files", rs -> {
            fileKind.put(rs.getLong(1), FileKind.fromRaw(rs.getString(2)));
        });
        vectorSource = store.getMeta("vector_source");
    }

    public List<SearchHit> search(String text, float[] queryVector, Set<FileKind> kinds, int limit,
                                  double zGate, Double wordWeightParam, boolean prefixLast) throws Exception {
        double wWeight = (wordWeightParam != null) ? wordWeightParam : (isCode() ? CODE_WORD_WEIGHT : WORD_WEIGHT);
        if (queryVector != null && !vectorsLoaded) {
            reload();
        }

        // 1. Semantic scoring
        Map<Long, SemanticResult> best = new HashMap<>();
        if (queryVector != null && queryVector.length == Vectors.DIMENSION && !rowFile.isEmpty() && matrixHalves != null) {
            float[] scores = score(queryVector);
            double[] meaning = computeMeaning(scores, rowModality, rowSetsScale);

            for (int row = 0; row < scores.length; row++) {
                long file = rowFile.get(row);
                if (kinds != null && !kinds.contains(fileKind.get(file))) continue;

                double z = meaning[row];
                SemanticResult prev = best.get(file);
                if (prev == null || z > prev.z) {
                    best.put(file, new SemanticResult(z, (double) scores[row], row));
                }
            }
        }

        // 2. Keyword FTS5 search
        List<String> terms = terms(text);
        List<String> identifier = isCode() ? CodeLookup.identifier(text) : null;
        List<KeywordMatch> words = new ArrayList<>();
        Map<Long, Double> coverage = new HashMap<>();

        List<String> patterns = (identifier != null)
            ? List.of(CodeLookup.phrase(identifier))
            : patterns(terms, prefixLast);

        if (!patterns.isEmpty()) {
            Set<Long> seen = new HashSet<>();
            String ftsMatch = String.join(" OR ", patterns);

            store.getDatabase().query("""
                SELECT s.file, s.id, snippet(fts, 1, '«', '»', '…', 12), fts.original FROM fts
                JOIN segments s ON s.id = fts.rowid
                WHERE fts MATCH ? ORDER BY bm25(fts, 3.0, 1.0) LIMIT 1000
                """, rs -> {
                long file = rs.getLong(1);
                if (words.size() >= 100) return;
                if (kinds != null && !kinds.contains(fileKind.get(file))) return;
                if (!seen.add(file)) return;

                String original = rs.getString(4);
                String snip = (original != null) ? SearchText.snippet(original, terms, 12) : null;
                if (snip == null) snip = rs.getString(3);

                words.add(new KeywordMatch(file, rs.getLong(2), snip));
            }, ftsMatch);

            if (!words.isEmpty()) {
                StringBuilder filesIn = new StringBuilder();
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) filesIn.append(",");
                    filesIn.append(words.get(i).file);
                }
                double patCount = (double) patterns.size();
                for (String p : patterns) {
                    store.getDatabase().query("""
                        SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid
                        WHERE fts MATCH ? AND s.file IN (""" + filesIn + ")", rs -> {
                        long f = rs.getLong(1);
                        coverage.merge(f, 1.0 / patCount, Double::sum);
                    }, p);
                }
            }
        }

        // 3. Fusion scoring
        boolean lookup = isLookup(terms) || identifier != null;
        double meaningWeight = lookup ? 0.5 : 1.0;
        double wordsWeight = lookup ? wWeight * 2.0 : wWeight;
        Set<FileKind> hinted = kindHints(text);

        Set<Long> candidates = new HashSet<>();
        for (Map.Entry<Long, SemanticResult> e : best.entrySet()) {
            if (e.getValue().z >= zGate) candidates.add(e.getKey());
        }
        for (Map.Entry<Long, Double> e : coverage.entrySet()) {
            if (e.getValue() >= 0.5) candidates.add(e.getKey());
        }

        Map<Long, Double> scored = new HashMap<>();
        for (Long file : candidates) {
            SemanticResult sem = best.get(file);
            double zVal = (sem != null) ? Math.max(sem.z, 0) : 0;
            double cov = coverage.getOrDefault(file, 0.0);
            double score = meaningWeight * zVal + wordsWeight * Math.pow(cov, 1.5);
            FileKind fk = fileKind.get(file);
            if (fk != null && hinted.contains(fk)) {
                score += 1.0;
            }
            scored.put(file, score);
        }

        // Sort candidates
        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(scored.entrySet());
        sorted.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : Long.compare(a.getKey(), b.getKey());
        });

        Map<Long, KeywordRankEntry> wordRank = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            KeywordMatch km = words.get(i);
            wordRank.putIfAbsent(km.file, new KeywordRankEntry(i + 1, km));
        }

        List<SearchHit> hits = new ArrayList<>();
        int countToTake = Math.min(limit, sorted.size());

        for (int rank = 0; rank < countToTake; rank++) {
            Map.Entry<Long, Double> entry = sorted.get(rank);
            long file = entry.getKey();
            double score = entry.getValue();

            SemanticResult semantic = best.get(file);
            KeywordRankEntry keyword = wordRank.get(file);

            boolean useMeaning = semantic != null && (semantic.z >= zGate || keyword == null);
            long segmentID = useMeaning ? rowSegment.get(semantic.row) : (keyword != null ? keyword.match.segment : -1L);
            if (segmentID < 0) continue;

            Double focus = null;
            if (isCode()) {
                CodePlaceResult cp = codePlace(file, segmentID, (rank < 10) ? identifier : null, text, terms,
                    keyword != null && keyword.match.segment == segmentID || identifier != null);
                segmentID = cp.segmentId;
                focus = cp.focus;
            }

            // Fetch file record
            String[] fileData = new String[2]; // [path, kind]
            double[] fileMtime = new double[1];
            long[] fileSize = new long[1];
            Map<String, String> info = new HashMap<>();

            store.getDatabase().query("SELECT path, kind, mtime, size, info FROM files WHERE id = ?", rs -> {
                fileData[0] = rs.getString(1);
                fileData[1] = rs.getString(2);
                fileMtime[0] = rs.getDouble(3);
                fileSize[0] = rs.getLong(4);
                String json = rs.getString(5);
                if (json != null && !json.isEmpty()) {
                    Map<String, String> m = gson.fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
                    if (m != null) info.putAll(m);
                }
            }, file);

            // Fetch segment record
            String[] segKind = new String[1];
            Double[] loc = new Double[2]; // [loc, locEnd]
            String[] storedExcerpt = new String[1];

            long finalSegId = segmentID;
            store.getDatabase().query("SELECT kind, loc, loc_end, excerpt FROM segments WHERE id = ?", rs -> {
                segKind[0] = rs.getString(1);
                double l = rs.getDouble(2);
                loc[0] = rs.wasNull() ? null : l;
                double le = rs.getDouble(3);
                loc[1] = rs.wasNull() ? null : le;
                storedExcerpt[0] = rs.getString(4);
            }, finalSegId);

            String snippet = null;
            if (keyword != null && keyword.match.segment == segmentID) {
                if (keyword.match.snippet != null && keyword.match.snippet.contains("«")) {
                    snippet = excerpt(keyword.match.snippet, 400);
                }
            }
            if (snippet == null && storedExcerpt[0] == null && loc[0] == null && keyword != null) {
                if (keyword.match.snippet != null && keyword.match.snippet.contains("«")) {
                    String[] matchedKind = new String[1];
                    store.getDatabase().query("SELECT kind FROM segments WHERE id = ?", rs -> {
                        matchedKind[0] = rs.getString(1);
                    }, keyword.match.segment);
                    if (!SegmentKind.name.getRaw().equals(matchedKind[0])) {
                        snippet = excerpt(keyword.match.snippet, 400);
                    }
                }
            }

            hits.add(new SearchHit(
                fileData[0] != null ? fileData[0] : "",
                FileKind.fromRaw(fileData[1]),
                score,
                semantic != null ? semantic.z : null,
                semantic != null ? semantic.cosine : null,
                keyword != null ? keyword.rank : null,
                SegmentKind.fromRaw(segKind[0]),
                segmentID,
                loc[0],
                loc[1],
                snippet != null ? snippet : storedExcerpt[0],
                fileMtime[0],
                fileSize[0],
                info,
                focus
            ));
        }

        return hits;
    }

    private static class CodePlaceResult {
        final long segmentId;
        final Double focus;
        CodePlaceResult(long segmentId, Double focus) {
            this.segmentId = segmentId;
            this.focus = focus;
        }
    }

    private CodePlaceResult codePlace(long file, long segmentId, List<String> identifier, String query,
                                      List<String> terms, boolean wordsHere) throws Exception {
        String name = query.trim().replaceAll("\\(\\)$", "");
        if (identifier != null) {
            long[] bestSeg = new long[]{-1};
            int[] bestLine = new int[]{-1};
            int[] bestStrength = new int[]{0};

            store.getDatabase().query("""
                SELECT s.id, s.loc, coalesce(fts.original, fts.body) FROM fts JOIN segments s ON s.id = fts.rowid
                WHERE fts MATCH ? AND s.file = ? ORDER BY s.loc LIMIT 300
                """, rs -> {
                if (bestStrength[0] >= 2) return;
                String text = rs.getString(3);
                int first = (int) rs.getDouble(2);
                CodeLookup.DefMatch def = CodeLookup.definition(name, text != null ? text : "", first);
                if (def != null && def.strength > bestStrength[0]) {
                    bestSeg[0] = rs.getLong(1);
                    bestLine[0] = def.line;
                    bestStrength[0] = def.strength;
                }
            }, CodeLookup.phrase(identifier), file);

            if (bestSeg[0] >= 0) {
                return new CodePlaceResult(bestSeg[0], (double) bestLine[0]);
            }
        }

        if (!wordsHere && identifier == null) {
            return new CodePlaceResult(segmentId, null);
        }

        String text = textOfSegment(segmentId);
        if (text == null) return new CodePlaceResult(segmentId, null);

        int[] first = new int[]{1};
        store.getDatabase().query("SELECT loc FROM segments WHERE id = ?", rs -> {
            first[0] = (int) rs.getDouble(1);
        }, segmentId);

        List<String> words = (identifier == null) ? terms : List.of(name.toLowerCase());
        Integer fl = CodeLookup.firstLine(words, text, first[0]);
        return new CodePlaceResult(segmentId, fl != null ? (double) fl : null);
    }

    public String textOfSegment(long segmentId) throws SQLException {
        String[] text = new String[]{null};
        store.getDatabase().query("SELECT coalesce(original, body) FROM fts WHERE rowid = ?",
            rs -> text[0] = rs.getString(1), segmentId);
        return text[0];
    }

    private float[] score(float[] query) {
        int rows = rowFile.size();
        float[] scores = new float[rows];
        if (matrixHalves == null || rows == 0) return scores;

        for (int r = 0; r < rows; r++) {
            scores[r] = Vectors.dotHalf(query, matrixHalves, r * Vectors.DIMENSION);
        }
        return scores;
    }

    private static double[] computeMeaning(float[] scores, List<Modality> modality, List<Boolean> setsScale) {
        Map<Modality, Double> means = computeModalityMeans(scores, modality, setsScale);
        double[] centered = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            centered[i] = (double) scores[i] - means.getOrDefault(modality.get(i), 0.0);
        }

        double count = 0.0, sum = 0.0;
        for (int i = 0; i < centered.length; i++) {
            if (setsScale == null || setsScale.get(i)) {
                count += 1.0;
                sum += centered[i];
            }
        }
        double mean = sum / Math.max(count, 1.0);

        double squares = 0.0;
        for (int i = 0; i < centered.length; i++) {
            if (setsScale == null || setsScale.get(i)) {
                double diff = centered[i] - mean;
                squares += diff * diff;
            }
        }
        double deviation = Math.max(Math.sqrt(squares / Math.max(count, 1.0)), 0.02);

        double[] z = new double[centered.length];
        for (int i = 0; i < centered.length; i++) {
            z[i] = (centered[i] - mean) / deviation;
        }
        return z;
    }

    private static Map<Modality, Double> computeModalityMeans(float[] scores, List<Modality> modality, List<Boolean> setsScale) {
        Map<Modality, Double> sum = new HashMap<>();
        Map<Modality, Integer> count = new HashMap<>();

        for (int i = 0; i < scores.length; i++) {
            if (setsScale == null || setsScale.get(i)) {
                Modality m = modality.get(i);
                sum.merge(m, (double) scores[i], Double::sum);
                count.merge(m, 1, Integer::sum);
            }
        }

        double totalSum = 0.0;
        int totalCount = 0;
        for (Map.Entry<Modality, Double> e : sum.entrySet()) {
            totalSum += e.getValue();
            totalCount += count.get(e.getKey());
        }
        double all = totalSum / (double) Math.max(totalCount, 1);

        Map<Modality, Double> out = new HashMap<>();
        for (Modality m : Modality.values()) {
            int n = count.getOrDefault(m, 0);
            if (n >= 20) {
                out.put(m, sum.get(m) / (double) n);
            } else {
                out.put(m, all);
            }
        }
        return out;
    }

    public static int strongCount(List<SearchHit> hits, double ratio) {
        if (hits.isEmpty()) return 0;
        double top = hits.get(0).getScore();
        if (top <= 0) return hits.size();
        int count = 0;
        for (SearchHit h : hits) {
            if (h.getScore() >= top * ratio) count++;
            else break;
        }
        return count;
    }

    public static boolean isLookup(List<String> terms) {
        if (terms.isEmpty()) return false;
        for (String t : terms) {
            boolean hasNum = false, hasLetter = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isDigit(c)) hasNum = true;
                if (Character.isLetter(c)) hasLetter = true;
            }
            if (!hasNum || (!hasLetter && t.length() < 4)) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "and", "or", "my", "me",
        "that", "this", "these", "those", "from", "about", "where", "what", "which", "who",
        "when", "is", "was", "are", "were", "be", "it", "its", "some", "any", "find", "show",
        "file", "files", "photo", "photos", "picture", "image", "images", "screenshot",
        "screenshots", "video", "videos", "pdf", "document", "documents", "recording",
        "এবং", "ও", "এই", "সেই", "একটি", "আমার", "থেকে", "জন্য", "সাথে", "মধ্যে", "কোথায়",
        "কোন", "কী", "কি", "যে", "ছবি", "স্ক্রিনশট", "ভিডিও", "ফাইল", "পিডিএফ",
        "في", "من", "على", "الى", "عن", "مع", "هذا", "هذه", "ذلك", "التي", "الذي", "اين", "ما",
        "صورة", "صور", "لقطة", "شاشة", "فيديو", "ملف", "مستند"
    );

    public static List<String> terms(String text) {
        String clean = SearchText.normalized(text).toLowerCase();
        String[] parts = clean.split("[^\\p{L}\\p{N}]+");
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.length() >= 2 && !STOPWORDS.contains(p) && seen.add(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static List<String> patterns(List<String> terms, boolean prefixLast) {
        List<String> res = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            boolean prefix = (prefixLast && i == terms.size() - 1) || SearchText.inflects(term);
            res.add("\"" + term + "\"" + (prefix ? "*" : ""));
        }
        return res;
    }

    private static final Map<String, FileKind> KIND_WORDS = Map.ofEntries(
        Map.entry("screenshot", FileKind.screenshot),
        Map.entry("screenshots", FileKind.screenshot),
        Map.entry("photo", FileKind.image),
        Map.entry("photos", FileKind.image),
        Map.entry("picture", FileKind.image),
        Map.entry("pictures", FileKind.image),
        Map.entry("image", FileKind.image),
        Map.entry("images", FileKind.image),
        Map.entry("pdf", FileKind.pdf),
        Map.entry("pdfs", FileKind.pdf),
        Map.entry("paper", FileKind.pdf),
        Map.entry("papers", FileKind.pdf),
        Map.entry("document", FileKind.doc),
        Map.entry("documents", FileKind.doc),
        Map.entry("note", FileKind.doc),
        Map.entry("notes", FileKind.doc),
        Map.entry("video", FileKind.video),
        Map.entry("videos", FileKind.video),
        Map.entry("clip", FileKind.video),
        Map.entry("audio", FileKind.audio),
        Map.entry("song", FileKind.audio),
        Map.entry("podcast", FileKind.audio),
        Map.entry("sound", FileKind.audio)
    );

    public static Set<FileKind> kindHints(String text) {
        Set<FileKind> set = new HashSet<>();
        String clean = SearchText.normalized(text).toLowerCase();
        String[] words = clean.split("[^\\p{L}\\p{N}]+");
        for (String w : words) {
            FileKind k = KIND_WORDS.get(w);
            if (k != null) set.add(k);
        }
        return set;
    }

    public static String excerpt(String text, int limit) {
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() > limit ? line.substring(0, limit) + "…" : line;
    }

    private static class SemanticResult {
        final double z;
        final double cosine;
        final int row;
        SemanticResult(double z, double cosine, int row) {
            this.z = z;
            this.cosine = cosine;
            this.row = row;
        }
    }

    private static class KeywordMatch {
        final long file;
        final long segment;
        final String snippet;
        KeywordMatch(long file, long segment, String snippet) {
            this.file = file;
            this.segment = segment;
            this.snippet = snippet;
        }
    }

    private static class KeywordRankEntry {
        final int rank;
        final KeywordMatch match;
        KeywordRankEntry(int rank, KeywordMatch match) {
            this.rank = rank;
            this.match = match;
        }
    }
}
