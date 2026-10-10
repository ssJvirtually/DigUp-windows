import fs from 'fs';
import path from 'path';
import ignore, { Ignore } from 'ignore';
import { Candidate, CrawlReport, IndexOptions } from '../shared/types.js';
import { Rules } from './Rules.js';

export class Crawler {
  public static crawl(roots: string[], options: IndexOptions): CrawlReport {
    const report: CrawlReport = {
      candidates: [],
      skipped: {},
      skippedFolders: {},
      datasets: {}
    };

    const seenPaths = new Set<string>();
    const projectRoots = new Set<string>();

    for (const root of roots) {
      if (!fs.existsSync(root)) continue;

      const gitIgnoreMap = new Map<string, Ignore>();

      const walk = (currentDir: string, isInsideProject: boolean) => {
        let entries: fs.Dirent[];
        try {
          entries = fs.readdirSync(currentDir, { withFileTypes: true });
        } catch {
          return;
        }

        // Check for gitignore in current folder
        if (options.code) {
          const gitIgnorePath = path.join(currentDir, '.gitignore');
          if (fs.existsSync(gitIgnorePath)) {
            try {
              const content = fs.readFileSync(gitIgnorePath, 'utf8');
              const ig = ignore().add(content);
              gitIgnoreMap.set(currentDir, ig);
            } catch {}
          }
        }

        let isCurrentProject = isInsideProject;
        if (!options.code && !isInsideProject && Rules.isProjectRoot(currentDir)) {
          isCurrentProject = true;
          projectRoots.add(currentDir);
        }

        // Folders first
        for (const entry of entries) {
          const fullPath = path.join(currentDir, entry.name);

          if (entry.name.startsWith('.')) {
            continue; // Skip hidden files and directories
          }

          if (entry.isDirectory()) {
            const skipReason = Rules.skipReasonForFolder(fullPath, options.excludedFolders) ||
              (options.code ? Rules.codeSkipReason(fullPath) : null);

            if (skipReason) {
              report.skippedFolders[skipReason] = (report.skippedFolders[skipReason] || 0) + 1;
              continue;
            }

            walk(fullPath, isCurrentProject);
            continue;
          }

          if (entry.isFile()) {
            if (seenPaths.has(fullPath)) continue;

            // Inside code projects (in standard search), only screenshots are indexed
            if (!options.code && isCurrentProject && !Rules.isScreenshot(fullPath)) {
              report.skipped['inside a code project'] = (report.skipped['inside a code project'] || 0) + 1;
              continue;
            }

            const classification = options.code
              ? Rules.classifyCode(fullPath, options)
              : Rules.classify(fullPath, options);

            switch (classification.type) {
              case 'ignore':
                report.skipped['other type'] = (report.skipped['other type'] || 0) + 1;
                break;
              case 'skip':
                report.skipped[classification.reason] = (report.skipped[classification.reason] || 0) + 1;
                break;
              case 'index': {
                try {
                  const stat = fs.statSync(fullPath);
                  if (options.code) {
                    const limit = fullPath.endsWith('.ipynb') ? options.maxNotebookBytes : options.maxCodeBytes;
                    if (stat.size > limit) {
                      report.skipped['too big to be written by hand'] = (report.skipped['too big to be written by hand'] || 0) + 1;
                      break;
                    }

                    // Check gitignore
                    let isIgnored = false;
                    for (const [dir, ig] of gitIgnoreMap.entries()) {
                      if (fullPath.startsWith(dir)) {
                        const rel = path.relative(dir, fullPath).replace(/\\/g, '/');
                        if (ig.ignores(rel)) {
                          isIgnored = true;
                          break;
                        }
                      }
                    }
                    if (isIgnored) {
                      report.skipped['ignored by git'] = (report.skipped['ignored by git'] || 0) + 1;
                      break;
                    }
                  }

                  seenPaths.add(fullPath);
                  report.candidates.push({
                    path: fullPath,
                    kind: classification.kind,
                    size: stat.size,
                    mtime: stat.mtimeMs / 1000,
                    inode: Number(stat.ino) || 0,
                    device: Number(stat.dev) || 0
                  });
                } catch {
                  report.skipped['unreadable'] = (report.skipped['unreadable'] || 0) + 1;
                }
                break;
              }
            }
          }
        }
      };

      walk(root, false);
    }

    return report;
  }
}
