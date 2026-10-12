package com.digup.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Filesystem monitor watching indexed folders with event debouncing.
 */
public final class FolderWatcher {

    public interface ChangeListener {
        void onChange();
    }

    private final List<File> folders = new ArrayList<>();
    private final ChangeListener listener;
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> debounceTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private WatchService watchService;
    private final Map<WatchKey, Path> keyPaths = new HashMap<>();

    public FolderWatcher(ChangeListener listener) {
        this.listener = listener;
    }

    public synchronized void setFolders(List<File> newFolders) {
        stop();
        folders.clear();
        folders.addAll(newFolders);
        start();
    }

    public synchronized void start() {
        if (folders.isEmpty() || running.get()) return;
        running.set(true);

        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (File f : folders) {
                if (f.exists() && f.isDirectory()) {
                    registerTree(f.toPath());
                }
            }

            Thread thread = new Thread(this::watchLoop, "DigUp-FolderWatcher");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                try {
                    WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                    keyPaths.put(key, dir);
                } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            Path dir = keyPaths.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                // Trigger debounced notification
                triggerDebounced();
            }

            boolean valid = key.reset();
            if (!valid) {
                keyPaths.remove(key);
            }
        }
    }

    private synchronized void triggerDebounced() {
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }
        debounceTask = debounceExecutor.schedule(() -> {
            if (listener != null) {
                listener.onChange();
            }
        }, 1500, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
        }
        keyPaths.clear();
    }
}
