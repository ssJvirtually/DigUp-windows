import path from 'path';
import fs from 'fs';
import { FileKind, IndexOptions } from '../shared/types.js';

export type Classification = 
  | { type: 'index'; kind: FileKind }
  | { type: 'skip'; reason: string }
  | { type: 'ignore' };

export class Rules {
  public static readonly skippedFolderNames = new Set([
    'node_modules',
    'bower_components',
    'venv',
    '.venv',
    'site-packages',
    '__pycache__',
    'DerivedData',
    'Pods',
    'Carthage',
    '$Recycle.Bin',
    'System Volume Information'
  ]);

  public static readonly projectMarkers = [
    '.git',
    'package.json',
    'Package.swift',
    'pyproject.toml',
    'setup.py',
    'requirements.txt',
    'Cargo.toml',
    'go.mod',
    'pom.xml',
    'build.gradle',
    'build.gradle.kts',
    'Gemfile',
    'composer.json',
    'CMakeLists.txt',
    '.sln'
  ];

  public static readonly docExtensions = new Set([
    'txt', 'text', 'md', 'markdown', 'rtf', 'docx', 'doc', 'odt', 'html', 'htm'
  ]);

  public static readonly codeExtensions = new Set([
    'py', 'ipynb', 'c', 'h', 'cc', 'cpp', 'cxx', 'hpp', 'hh', 'm', 'mm', 'swift',
    'tsx', 'cts', 'js', 'jsx', 'mjs', 'cjs', 'go', 'rs', 'java', 'kt', 'kts',
    'scala', 'rb', 'php', 'cs', 'fs', 'sh', 'bash', 'zsh', 'fish', 'ps1',
    'lua', 'pl', 'r', 'jl', 'dart', 'sql', 'vue', 'svelte', 'css', 'scss', 'sass',
    'less', 'json', 'jsonl', 'yaml', 'yml', 'toml', 'xml', 'plist', 'gradle',
    'cmake', 'mk', 'lock', 'log', 'csv', 'tsv', 'ini', 'cfg', 'conf', 'proto',
    'graphql', 'tf', 'nix', 'zig', 'hs', 'ex', 'exs', 'erl', 'clj', 'elm', 'ml',
    'vim', 'el', 'asm', 'metal', 'glsl', 'hlsl', 'wgsl', 'cu', 'pyi'
  ]);

  public static readonly sourceExtensions = new Set([
    'py', 'pyi', 'pyx', 'c', 'h', 'cc', 'cpp', 'cxx', 'hpp', 'hh', 'hxx', 'm', 'mm',
    'swift', 'ts', 'tsx', 'mts', 'cts', 'js', 'jsx', 'mjs', 'cjs', 'go', 'rs', 'java',
    'kt', 'kts', 'scala', 'groovy', 'rb', 'php', 'cs', 'fs', 'sh', 'bash', 'zsh', 'fish',
    'ps1', 'lua', 'pl', 'pm', 'r', 'jl', 'dart', 'sql', 'vue', 'svelte', 'astro',
    'html', 'htm', 'css', 'scss', 'sass', 'less', 'gradle', 'cmake', 'mk', 'proto',
    'graphql', 'gql', 'tf', 'hcl', 'nix', 'zig', 'hs', 'ex', 'exs', 'erl', 'clj', 'cljs',
    'elm', 'ml', 'mli', 'vim', 'el', 'lisp', 'asm', 's', 'metal', 'glsl', 'hlsl', 'wgsl',
    'cu', 'cuh', 'ino', 'v', 'sv', 'tex'
  ]);

  public static readonly codeTextExtensions = new Set([
    'md', 'markdown', 'rst', 'adoc', 'org', 'txt', 'yaml', 'yml', 'toml'
  ]);

  public static readonly codeFileNames = new Set([
    'Makefile', 'makefile', 'GNUmakefile', 'Dockerfile', 'Containerfile', 'Justfile',
    'justfile', 'Rakefile', 'Gemfile', 'Podfile', 'Brewfile', 'Procfile', 'Vagrantfile',
    'Jenkinsfile'
  ]);

  public static readonly codeSkippedFolderNames = new Set([
    'vendor', 'Vendor', 'vendored', 'third_party', 'third-party', 'thirdparty', '3rdparty',
    'external', 'extern', 'deps', 'build', 'Build', 'dist', 'out', 'target', 'obj', 'bin',
    'coverage', 'htmlcov', 'generated', '__generated__', 'wandb', 'mlruns', 'lightning_logs'
  ]);

  public static readonly imageExtensions = new Set([
    'jpg', 'jpeg', 'png', 'heic', 'webp', 'gif', 'bmp', 'tiff', 'tif', 'raw', 'cr2', 'nef', 'arw', 'dng'
  ]);

  public static readonly audioExtensions = new Set([
    'mp3', 'wav', 'm4a', 'flac', 'aac', 'ogg', 'oga', 'opus', 'aiff', 'wma'
  ]);

  public static readonly videoExtensions = new Set([
    'mp4', 'mov', 'mkv', 'avi', 'webm', 'wmv', 'm4v', 'flv', '3gp'
  ]);

  public static readonly screenshotPrefixes = [
    'Screenshot', 'Screen Shot', 'CleanShot', 'SCR-', 'Capture', 'Snip'
  ];

  public static isSecret(name: string): boolean {
    const lower = name.toLowerCase();
    const ext = path.extname(lower).slice(1);
    const secretTypes = new Set([
      'pem', 'key', 'p12', 'pfx', 'keystore', 'jks', 'ppk', 'kdbx', 'gpg', 'asc',
      'crt', 'cer', 'der', 'p8'
    ]);
    if (secretTypes.has(ext)) return true;
    return [
      'id_rsa', 'id_dsa', 'id_ecdsa', 'id_ed25519', '.env', 'secret', 'credential',
      '.netrc', '.npmrc', '.pypirc', 'htpasswd', '.htpasswd'
    ].some((p) => lower.startsWith(p));
  }

  public static isScreenshot(filePath: string): boolean {
    const name = path.basename(filePath);
    return this.screenshotPrefixes.some((p) => name.startsWith(p));
  }

  public static isProjectRoot(dirPath: string): boolean {
    return this.projectMarkers.some((marker) => fs.existsSync(path.join(dirPath, marker)));
  }

  public static skipReasonForFolder(folderPath: string, excludedFolders: string[]): string | null {
    const base = path.basename(folderPath);
    if (this.skippedFolderNames.has(base)) return 'ignored folder';
    if (excludedFolders.some((exc) => folderPath === exc || folderPath.startsWith(exc + path.sep))) {
      return 'user excluded folder';
    }
    return null;
  }

  public static codeSkipReason(folderPath: string): string | null {
    const base = path.basename(folderPath);
    if (this.codeSkippedFolderNames.has(base)) return 'vendored or built code';
    return null;
  }

  public static classify(filePath: string, options: IndexOptions): Classification {
    const name = path.basename(filePath);
    const ext = path.extname(filePath).slice(1).toLowerCase();

    if (options.excludedExtensions.has(ext)) {
      return { type: 'skip', reason: 'excluded type' };
    }

    if (ext === 'pdf') {
      return { type: 'index', kind: 'pdf' };
    }

    if (this.imageExtensions.has(ext)) {
      return { type: 'index', kind: this.isScreenshot(filePath) ? 'screenshot' : 'image' };
    }

    if (this.audioExtensions.has(ext)) {
      return { type: 'index', kind: 'audio' };
    }

    if (this.videoExtensions.has(ext)) {
      return { type: 'index', kind: 'video' };
    }

    if (this.docExtensions.has(ext)) {
      return { type: 'index', kind: 'doc' };
    }

    if (this.codeExtensions.has(ext)) {
      return options.includeCode
        ? { type: 'index', kind: 'doc' }
        : { type: 'skip', reason: 'code or data file' };
    }

    return { type: 'ignore' };
  }

  public static classifyCode(filePath: string, options: IndexOptions): Classification {
    const name = path.basename(filePath);
    const ext = path.extname(filePath).slice(1).toLowerCase();

    if (options.excludedExtensions.has(ext)) {
      return { type: 'skip', reason: 'excluded type' };
    }

    if (this.isSecret(name)) {
      return { type: 'skip', reason: 'key or password file' };
    }

    const lower = name.toLowerCase();
    if (lower.endsWith('.min.js') || lower.endsWith('.min.css')) {
      return { type: 'skip', reason: 'minified' };
    }

    if (['license', 'licence', 'copying', 'notice'].some((p) => lower.startsWith(p))) {
      return { type: 'skip', reason: 'license' };
    }

    if (
      this.sourceExtensions.has(ext) ||
      this.codeTextExtensions.has(ext) ||
      ext === 'ipynb' ||
      this.codeFileNames.has(name) ||
      name.startsWith('Dockerfile')
    ) {
      return { type: 'index', kind: 'code' };
    }

    if (this.codeExtensions.has(ext)) {
      return { type: 'skip', reason: 'data file' };
    }

    return { type: 'ignore' };
  }
}
