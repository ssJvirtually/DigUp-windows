package com.digup.engine;

import com.digup.model.*;
import com.digup.worker.WorkerClient;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main engine coordinating database storage, crawl loops, indexers, and hybrid searchers.
 */
public final class Engine {

    private final AppSettings settings;
    private final Path appDataDir;

    private IndexStore mainStore;
    private IndexStore codeStore;
    private Searcher mainSearcher;
    private Searcher codeSearcher;
    private WorkerClient worker;
    private Indexer indexer;
    private FolderWatcher watcher;

    private final ExecutorService backgroundPool = Executors.newCachedThreadPool();
    private boolean initialized = false;

    public interface StatusListener {
        void onStatusChange(String status, int pending, int total);
    }

    private StatusListener statusListener;

    public Engine(AppSettings settings) {
        this.settings = settings;
        this.appDataDir = AppSettings.getAppDataDir();
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public synchronized void start() throws Exception {
        if (initialized) return;

        // Initialize stores
        mainStore = new IndexStore(appDataDir, IndexStore.MAIN_FILE);
        codeStore = new IndexStore(appDataDir, IndexStore.CODE_FILE);

        // Scope
        mainStore.setScope(settings.roots);
        codeStore.setScope(settings.codeRoots);

        // Worker
        worker = new WorkerClient(false);

        // Searchers
        mainSearcher = new Searcher(mainStore, true);
        codeSearcher = new Searcher(codeStore, true);

        // Indexer
        IndexOptions options = new IndexOptions();
        options.excludedFolders.addAll(settings.excludedFolders);
        options.excludedExtensions.addAll(settings.excludedTypes);

        indexer = new Indexer(mainStore, worker, options);
        indexer.setListener((pending, total, curFile) -> {
            if (statusListener != null) {
                statusListener.onStatusChange("Indexing: " + curFile, pending, total);
            }
        });
        backgroundPool.submit(indexer::runLoop);

        // Watcher
        watcher = new FolderWatcher(this::triggerCrawl);
        List<File> watchFolders = new ArrayList<>();
        for (String r : settings.roots) watchFolders.add(new File(r));
        for (String r : settings.codeRoots) watchFolders.add(new File(r));
        watcher.setFolders(watchFolders);

        initialized = true;

        // Initial crawl in background
        backgroundPool.submit(this::triggerCrawl);
    }

    public synchronized void triggerCrawl() {
        if (!initialized) return;
        try {
            if (statusListener != null) statusListener.onStatusChange("Crawling folders...", 0, 0);

            IndexOptions options = new IndexOptions();
            options.excludedFolders.addAll(settings.excludedFolders);
            options.excludedExtensions.addAll(settings.excludedTypes);

            // Crawl main roots
            List<File> mainRoots = settings.roots.stream().map(File::new).toList();
            Crawler.CrawlReport report = Crawler.crawl(mainRoots, options);

            List<FileRecord> existing = mainStore.filesUnder(settings.roots);
            Map<String, FileRecord> byPath = new HashMap<>();
            for (FileRecord fr : existing) byPath.put(fr.getPath(), fr);

            for (Candidate c : report.candidates) {
                FileRecord prev = byPath.get(c.path);
                if (prev == null) {
                    mainStore.insert(c);
                } else if (prev.getMtime() != c.mtime || prev.getSize() != c.size) {
                    mainStore.requeue(prev.getId(), c);
                }
            }

            // Prune unreferenced
            mainStore.prune(settings.roots);

            // Crawl code roots if enabled
            if (!settings.codeRoots.isEmpty()) {
                options.code = true;
                List<File> codeRoots = settings.codeRoots.stream().map(File::new).toList();
                Crawler.CrawlReport codeReport = Crawler.crawl(codeRoots, options);

                List<FileRecord> existingCode = codeStore.filesUnder(settings.codeRoots);
                Map<String, FileRecord> codeByPath = new HashMap<>();
                for (FileRecord fr : existingCode) codeByPath.put(fr.getPath(), fr);

                for (Candidate c : codeReport.candidates) {
                    FileRecord prev = codeByPath.get(c.path);
                    if (prev == null) {
                        codeStore.insert(c);
                    } else if (prev.getMtime() != c.mtime || prev.getSize() != c.size) {
                        codeStore.requeue(prev.getId(), c);
                    }
                }
                codeStore.prune(settings.codeRoots);
            }

            mainSearcher.reload();
            codeSearcher.reload();

            if (statusListener != null) statusListener.onStatusChange("Idle", 0, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<SearchHit> search(String query, Set<FileKind> kinds, boolean searchCode, int limit) throws Exception {
        if (!initialized || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Generate query vector
        float[] queryVec = null;
        try {
            float[][] v = worker.embedTexts(List.of("task: search result | query: " + query.trim()));
            if (v.length > 0) queryVec = v[0];
        } catch (Exception ignored) {}

        List<SearchHit> mainHits = mainSearcher.search(query, queryVec, kinds, limit, 2.0, null, true);
        List<SearchHit> all = new ArrayList<>(mainHits);

        if (searchCode && (kinds == null || kinds.contains(FileKind.code))) {
            List<SearchHit> codeHits = codeSearcher.search(query, queryVec, null, limit, 2.0, null, true);
            all.addAll(codeHits);
        }

        // Rank combined results
        all.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    public IndexStore getMainStore() { return mainStore; }
    public IndexStore getCodeStore() { return codeStore; }
    public WorkerClient getWorker() { return worker; }

    public synchronized void stop() {
        if (indexer != null) indexer.stop();
        if (watcher != null) watcher.stop();
        if (worker != null) worker.close();
        if (mainStore != null) mainStore.close();
        if (codeStore != null) codeStore.close();
        backgroundPool.shutdownNow();
        initialized = false;
    }
}
