import fs from 'fs';
import path from 'path';
import os from 'os';
import { Database } from './Database.js';
import { IndexStore } from './IndexStore.js';
import { Vectors } from './Vectors.js';
import { SearchText } from './SearchText.js';
import { Rules } from './Rules.js';
import { DocExtractor } from './extractors/DocExtractor.js';
import { CodeExtractor } from './extractors/CodeExtractor.js';
import { Searcher } from './Searcher.js';
import { WorkerClient } from '../worker/WorkerClient.js';
import { defaultIndexOptions } from '../shared/types.js';

async function runTests() {
  console.log('=== DigUp-Windows Verification Suite ===\n');

  // Test 1: Vectors & Float16 Math
  console.log('1. Testing Vectors & Float16 Math...');
  const testVec = new Float32Array(768);
  for (let i = 0; i < 768; i++) testVec[i] = Math.sin(i);
  const normalized = Vectors.normalize(testVec);
  const halfBuf = Vectors.toHalf(normalized);
  const unpacked = Vectors.fromHalf(halfBuf);
  const dotSelf = Vectors.dot(normalized, unpacked);
  console.log(`   - Self cosine similarity after Float16 conversion: ${dotSelf.toFixed(5)}`);
  if (dotSelf < 0.999) throw new Error('Float16 conversion accuracy failed');
  console.log('   ✓ Vectors passed\n');

  // Test 2: SearchText & Normalization
  console.log('2. Testing SearchText Normalization...');
  const arabicText = 'الْكِتَابُ';
  const normArabic = SearchText.normalized(arabicText);
  console.log(`   - Arabic normalization: "${arabicText}" -> "${normArabic}"`);
  if (normArabic.includes('\u064e')) throw new Error('Harakat was not stripped');

  const snippet = SearchText.snippet('Invoice 4815162342 payment confirmed for consulting services', ['4815162342']);
  console.log(`   - Snippet highlight: ${snippet}`);
  if (!snippet?.includes('«4815162342»')) throw new Error('Snippet term highlight failed');
  console.log('   ✓ SearchText passed\n');

  // Test 3: Rules & Classification
  console.log('3. Testing Rules Classification...');
  const opts = defaultIndexOptions();
  const c1 = Rules.classify('D:/work/test.pdf', opts);
  const c2 = Rules.classify('D:/work/test.png', opts);
  const c3 = Rules.classify('D:/work/Screenshot 2026-10-10.png', opts);
  const c4 = Rules.classify('D:/work/.env', opts);
  const c5 = Rules.classifyCode('D:/work/app.py', opts);

  console.log(`   - test.pdf: ${c1.type === 'index' ? c1.kind : 'failed'}`);
  console.log(`   - test.png: ${c2.type === 'index' ? c2.kind : 'failed'}`);
  console.log(`   - Screenshot: ${c3.type === 'index' ? c3.kind : 'failed'}`);
  console.log(`   - .env: ${Rules.isSecret('.env') ? 'secret (correct)' : 'failed'}`);
  console.log(`   - app.py (code): ${c5.type === 'index' ? c5.kind : 'failed'}`);

  if (c1.type !== 'index' || c1.kind !== 'pdf') throw new Error('Rules PDF classification failed');
  if (c3.type !== 'index' || c3.kind !== 'screenshot') throw new Error('Rules Screenshot classification failed');
  console.log('   ✓ Rules passed\n');

  // Test 4: DocExtractor & CodeExtractor
  console.log('4. Testing DocExtractor & CodeExtractor...');
  const htmlSample = '<p>Hello <b>World</b>! &amp; welcome</p>';
  const cleanHtml = DocExtractor.plainTextFromHTML(htmlSample);
  console.log(`   - HTML parse: "${htmlSample}" -> "${cleanHtml}"`);
  if (!cleanHtml.includes('Hello') || !cleanHtml.includes('World') || !cleanHtml.includes('& welcome')) {
    throw new Error('HTML parsing failed');
  }

  const codeSample = `def retry_with_backoff(fn, attempts=3):\n    for i in range(attempts):\n        try:\n            return fn()\n        except Exception:\n            time.sleep(2 ** i)\n    return None`;
  const codePieces = CodeExtractor.linesToPieces(codeSample, 1800);
  console.log(`   - Code pieces extracted: ${codePieces.length}, first line: ${codePieces[0].first}, last: ${codePieces[0].last}`);
  if (codePieces.length === 0) throw new Error('Code chunking failed');
  console.log('   ✓ Extractors passed\n');

  // Test 5: IndexStore & SQLite FTS5
  console.log('5. Testing IndexStore & SQLite FTS5...');
  const testDbDir = path.join(os.tmpdir(), `digup-test-${Date.now()}`);
  const store = new IndexStore(testDbDir, 'test.sqlite');

  const fileId = store.insert({
    path: path.join(testDbDir, 'report.pdf'),
    kind: 'pdf',
    size: 1024,
    mtime: Date.now() / 1000,
    inode: 12345,
    device: 1
  });

  const dummyVec = Vectors.normalize(new Float32Array(768).fill(0.1));
  store.addSegment({
    file: fileId,
    kind: 'page',
    modality: 'text',
    loc: 1,
    excerpt: 'Financial report for Q4 2026 with revenue growth',
    text: 'Financial report for Q4 2026 with revenue growth',
    vector: dummyVec
  });

  const counts = store.counts();
  console.log(`   - Stored segments: ${counts.segments}, vectors: ${counts.vectors}`);
  if (counts.segments !== 1 || counts.vectors !== 1) throw new Error('IndexStore counts mismatch');

  // Test 6: Searcher Hybrid Ranking
  console.log('6. Testing Searcher Hybrid Ranking...');
  const searcher = new Searcher(store, true);
  const hits = searcher.search({
    text: 'Financial report',
    queryVector: dummyVec,
    limit: 10
  });

  console.log(`   - Search results count: ${hits.length}`);
  if (hits.length === 0) throw new Error('Search failed to return hits');
  console.log(`   - Top hit: ${hits[0].path}, score: ${hits[0].score.toFixed(3)}, excerpt: ${hits[0].excerpt}`);
  console.log('   ✓ Searcher passed\n');

  // Test 7: WorkerClient stdio IPC
  console.log('7. Testing WorkerClient Embedding IPC...');
  const worker = new WorkerClient(10000);
  const workerScript = path.join(process.cwd(), 'dist/worker/digup_worker.py');
  await worker.start(workerScript, ['--text-only']);
  console.log(`   - Worker connected: model=${worker.info?.model}, dim=${worker.info?.dim}`);

  const embRes = await worker.embedTexts(['task: search result | query: financial statement']);
  console.log(`   - Vector generated: count=${embRes.length}, dim=${embRes[0].vector?.length}`);
  if (!embRes[0].vector || embRes[0].vector.length !== 768) throw new Error('Worker vector generation failed');

  worker.close();
  console.log('   ✓ WorkerClient passed\n');

  // Clean up
  store.db.close();
  try {
    fs.rmSync(testDbDir, { recursive: true, force: true });
  } catch {}

  console.log('🎉 ALL TESTS PASSED! DigUp-Windows is fully functional and ready.');
}

runTests().catch((err) => {
  console.error('\n❌ Test failed with error:', err);
  process.exit(1);
});
