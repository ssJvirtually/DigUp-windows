package com.digup.engine;

import com.digup.model.Candidate;
import com.digup.model.FileKind;
import com.digup.model.IndexOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Traverses directories, honors ignore rules and gitignore, and produces candidate lists.
 */
public final class Crawler {

    public static class CrawlReport {
        public final List<Candidate> candidates = new ArrayList<>();
        public final Map<String, Integer> skipped = new HashMap<>();
        public final Map<String, Integer> skippedFolders = new HashMap<>();
    }

    public static CrawlReport crawl(List<File> roots, IndexOptions options) {
        CrawlReport report = new CrawlReport();
        Set<String> visitedPaths = new HashSet<>();
        GitRepos gitRepos = new GitRepos();

        for (File root : roots) {
            if (!root.exists() || !root.isDirectory()) continue;
            if (options.code) {
                gitRepos.enterRoot(root.getAbsolutePath());
            }

            try {
                Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        File folder = dir.toFile();
                        String name = folder.getName();

                        // Hidden folder skip (unless root)
                        if (!dir.equals(root.toPath()) && name.startsWith(".")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        String skipReason = Rules.skipReasonForFolder(folder, options);
                        if (skipReason == null && options.code) {
                            skipReason = Rules.codeSkipReasonForFolder(folder);
                        }
                        if (skipReason != null) {
                            report.skippedFolders.merge(skipReason, 1, Integer::sum);
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        if (options.code) {
                            gitRepos.enter(folder.getAbsolutePath());
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                        File file = path.toFile();
                        String absPath = file.getAbsolutePath().replace('\\', '/');

                        if (file.getName().startsWith(".") && !file.getName().equals(".gitignore")) {
                            return FileVisitResult.CONTINUE;
                        }

                        Rules.Classification classification = options.code
                            ? Rules.classifyCode(file, options)
                            : Rules.classify(file, options);

                        if (classification.type == Rules.Classification.Type.IGNORE) {
                            report.skipped.merge("ignored type", 1, Integer::sum);
                            return FileVisitResult.CONTINUE;
                        }
                        if (classification.type == Rules.Classification.Type.SKIP) {
                            report.skipped.merge(classification.reason, 1, Integer::sum);
                            return FileVisitResult.CONTINUE;
                        }

                        if (options.code) {
                            if (attrs.size() > options.maxCodeBytes) {
                                report.skipped.merge("file exceeds max code size", 1, Integer::sum);
                                return FileVisitResult.CONTINUE;
                            }
                            if (gitRepos.ignores(file.getAbsolutePath())) {
                                report.skipped.merge("ignored by git", 1, Integer::sum);
                                return FileVisitResult.CONTINUE;
                            }
                        }

                        if (visitedPaths.add(absPath)) {
                            double mtime = attrs.lastModifiedTime().toMillis() / 1000.0;
                            long inode = (long) absPath.hashCode();
                            long device = 1L;
                            report.candidates.add(new Candidate(
                                absPath, classification.kind, attrs.size(), mtime, inode, device
                            ));
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception e) {
                // Ignore traversal failures
            }
        }
        return report;
    }

    private static class GitRepos {
        private final Map<String, Set<String>> repoFiles = new HashMap<>();
        private final Map<String, String> folderRepo = new HashMap<>();

        public void enterRoot(String path) {
            File current = new File(path);
            while (current != null) {
                if (new File(current, ".git").exists()) {
                    String repoRoot = current.getAbsolutePath();
                    repoFiles.put(repoRoot, listGitFiles(repoRoot));
                    folderRepo.put(path, repoRoot);
                    return;
                }
                current = current.getParentFile();
            }
        }

        public void enter(String path) {
            File gitDir = new File(path, ".git");
            if (gitDir.exists()) {
                repoFiles.put(path, listGitFiles(path));
                folderRepo.put(path, path);
            }
        }

        public boolean ignores(String filePath) {
            File parent = new File(filePath).getParentFile();
            if (parent == null) return false;
            String repo = findRepo(parent.getAbsolutePath());
            if (repo == null || !repoFiles.containsKey(repo)) return false;
            Set<String> kept = repoFiles.get(repo);
            if (kept == null) return false;

            String rel = filePath.substring(repo.length());
            if (rel.startsWith("\\") || rel.startsWith("/")) rel = rel.substring(1);
            rel = rel.replace('\\', '/');
            return !kept.contains(rel);
        }

        private String findRepo(String folder) {
            if (folderRepo.containsKey(folder)) return folderRepo.get(folder);
            File f = new File(folder);
            while (f != null) {
                if (repoFiles.containsKey(f.getAbsolutePath())) {
                    folderRepo.put(folder, f.getAbsolutePath());
                    return f.getAbsolutePath();
                }
                f = f.getParentFile();
            }
            return null;
        }

        private Set<String> listGitFiles(String repoPath) {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "-C", repoPath, "ls-files", "-z", "--cached", "--others", "--exclude-standard");
                pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
                Process proc = pb.start();
                Set<String> set = new HashSet<>();
                try (java.io.InputStream in = proc.getInputStream()) {
                    ByteArrayBuffer bab = new ByteArrayBuffer();
                    int b;
                    while ((b = in.read()) != -1) {
                        if (b == 0) {
                            String s = bab.toStringAndReset();
                            if (!s.isEmpty()) set.add(s.replace('\\', '/'));
                        } else {
                            bab.append((byte) b);
                        }
                    }
                    String rem = bab.toStringAndReset();
                    if (!rem.isEmpty()) set.add(rem.replace('\\', '/'));
                }
                proc.waitFor();
                if (proc.exitValue() == 0) return set;
            } catch (Exception ignored) {}
            return null;
        }
    }

    private static class ByteArrayBuffer {
        private byte[] buf = new byte[256];
        private int count = 0;

        public void append(byte b) {
            if (count >= buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[count++] = b;
        }

        public String toStringAndReset() {
            String s = new String(buf, 0, count, java.nio.charset.StandardCharsets.UTF_8);
            count = 0;
            return s;
        }
    }
}
