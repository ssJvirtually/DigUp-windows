package com.digup;

import com.digup.engine.*;
import com.digup.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DigUpCoreTest {

    @Test
    public void testVectorsRoundtripAndMath() {
        float[] original = new float[Vectors.DIMENSION];
        for (int i = 0; i < original.length; i++) {
            original[i] = (float) Math.sin(i);
        }
        Vectors.normalize(original);

        // Vector length should be 1.0 after normalization
        float norm = (float) Math.sqrt(Vectors.dot(original, original));
        assertEquals(1.0f, norm, 1e-4f);

        // Float16 roundtrip
        byte[] halfBytes = Vectors.half(original);
        assertNotNull(halfBytes);
        assertEquals(Vectors.BYTES_PER_VEC, halfBytes.length);

        float[] restored = Vectors.floats(halfBytes);
        assertNotNull(restored);
        assertEquals(Vectors.DIMENSION, restored.length);

        // Dot product between original and float16 restored should be >= 0.999
        float cosine = Vectors.dot(original, restored);
        assertTrue(cosine > 0.999f, "Cosine similarity after fp16 roundtrip should be ~1.0");
    }

    @Test
    public void testSearchTextNormalizationAndSnippet() {
        String input = "Hello\u00A0World!\u200B This is a test file for DigUp desktop search.";
        String norm = SearchText.normalized(input);
        assertFalse(norm.contains("\u200B"));

        assertTrue(SearchText.hasWords("This is normal English text with letters."));
        assertFalse(SearchText.hasWords("12345 67890 234234 987987"));

        String text = "The quick brown fox jumps over the lazy dog";
        List<SearchText.Range> matches = SearchText.matches("brown", text);
        assertEquals(1, matches.size());
        assertEquals("brown", text.substring(matches.get(0).start, matches.get(0).end));

        String snip = SearchText.snippet(text, List.of("fox"), 10);
        assertNotNull(snip);
        assertTrue(snip.contains("«fox»"));
    }

    @Test
    public void testCodeLookup() {
        List<String> words = CodeLookup.identifier("quantize_row_q8_0_ref");
        assertNotNull(words);
        assertTrue(words.contains("quantize"));
        assertTrue(words.contains("ref"));

        List<String> camelWords = CodeLookup.identifier("findBestMatch");
        assertNotNull(camelWords);
        assertTrue(camelWords.contains("find"));
        assertTrue(camelWords.contains("match"));

        // Plain words are not code identifiers
        assertNull(CodeLookup.identifier("plain"));

        String sampleCode = """
            import java.util.*;

            public class MathService {
                public static int calculateTotal(int a, int b) {
                    return a + b;
                }
            }
            """;
        CodeLookup.DefMatch def = CodeLookup.definition("calculateTotal", sampleCode, 1);
        assertNotNull(def);
        assertEquals(4, def.line);
        assertEquals(2, def.strength); // Function definition strength
    }

    @Test
    public void testRulesClassification() {
        File docFile = new File("notes.md");
        IndexOptions options = new IndexOptions();
        Rules.Classification docCls = Rules.classify(docFile, options);
        assertEquals(Rules.Classification.Type.INDEX, docCls.type);
        assertEquals(FileKind.doc, docCls.kind);

        File shotFile = new File("Screenshot 2026-10-11 at 12.00.png");
        Rules.Classification shotCls = Rules.classify(shotFile, options);
        assertEquals(Rules.Classification.Type.INDEX, shotCls.type);
        assertEquals(FileKind.screenshot, shotCls.kind);

        File secret = new File(".env");
        assertTrue(Rules.isSecret(secret.getName()));

        File pem = new File("server.key");
        assertTrue(Rules.isSecret(pem.getName()));
    }

    @Test
    public void testIndexStoreAndFts5(@TempDir Path tempDir) throws Exception {
        IndexStore store = new IndexStore(tempDir, IndexStore.MAIN_FILE);
        assertNotNull(store.getDatabase());

        // Test metadata
        store.setMeta("test_key", "test_val");
        assertEquals("test_val", store.getMeta("test_key"));

        // Insert candidate file
        Candidate c = new Candidate(
            tempDir.resolve("doc.txt").toString(),
            FileKind.doc,
            1024,
            System.currentTimeMillis() / 1000.0,
            1234L,
            1L
        );
        long fileId = store.insert(c);
        assertTrue(fileId > 0);

        // Add text segment
        float[] dummyVec = new float[Vectors.DIMENSION];
        dummyVec[0] = 1.0f;
        store.addSegment(fileId, SegmentKind.chunk, Modality.text, 1.0, null, "First excerpt", "Architecture and DeepMind multimodal search", dummyVec);

        store.finish(fileId, "done", null, Map.of("chars", "500"));

        // Verify counts
        IndexStore.Counts counts = store.counts();
        assertEquals(1, counts.segments);
        assertEquals(1, counts.vectors);

        // Test searcher over the created store
        Searcher searcher = new Searcher(store, true);
        assertEquals(1, searcher.getVectorCount());

        List<SearchHit> hits = searcher.search("multimodal", dummyVec, null, 10, 0.0, null, true);
        assertFalse(hits.isEmpty());
        assertEquals("doc.txt", hits.get(0).getFileName());
        assertTrue(hits.get(0).getScore() > 0);

        store.close();
    }
}
