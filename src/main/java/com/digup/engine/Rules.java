package com.digup.engine;

import com.digup.model.FileKind;
import com.digup.model.IndexOptions;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;

/**
 * File classification rules, project root markers, and secret detection.
 */
public final class Rules {

    public static final Set<String> SKIPPED_FOLDER_NAMES = Set.of(
        "node_modules", "bower_components", "venv", "site-packages", "__pycache__",
        "DerivedData", "Pods", "Carthage", ".git", ".svn", ".hg", "bin", "obj", "target"
    );

    public static final Set<String> PROJECT_MARKERS = Set.of(
        ".git", "package.json", "Package.swift", "pyproject.toml", "setup.py",
        "requirements.txt", "Cargo.toml", "go.mod", "pom.xml", "build.gradle",
        "build.gradle.kts", "Gemfile", "composer.json", "CMakeLists.txt"
    );

    public static final Set<String> DOC_EXTENSIONS = Set.of(
        "txt", "text", "md", "markdown", "rtf", "docx", "doc", "odt", "html", "htm"
    );

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "heic", "webp", "gif", "tiff", "tif", "bmp"
    );

    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
        "wav", "mp3", "m4a", "aac", "flac", "ogg", "wma", "aiff"
    );

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv", "flv"
    );

    public static final Set<String> SOURCE_EXTENSIONS = Set.of(
        "py", "pyi", "pyx", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "m", "mm",
        "swift", "ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "go", "rs", "java",
        "kt", "kts", "scala", "groovy", "rb", "php", "cs", "fs", "sh", "bash", "zsh", "fish",
        "ps1", "lua", "pl", "pm", "r", "jl", "dart", "sql", "vue", "svelte", "astro", "html",
        "htm", "css", "scss", "sass", "less", "gradle", "cmake", "mk", "proto", "graphql",
        "gql", "tf", "hcl", "nix", "zig", "hs", "ex", "exs", "erl", "clj", "cljs", "elm",
        "ml", "mli", "vim", "el", "lisp", "asm", "s", "metal", "glsl", "hlsl", "wgsl", "cu",
        "cuh", "ino", "v", "sv", "tex"
    );

    public static final Set<String> CODE_TEXT_EXTENSIONS = Set.of(
        "md", "markdown", "rst", "adoc", "org", "txt", "yaml", "yml", "toml"
    );

    public static final Set<String> CODE_FILE_NAMES = Set.of(
        "Makefile", "makefile", "GNUmakefile", "Dockerfile", "Containerfile", "Justfile",
        "justfile", "Rakefile", "Gemfile", "Podfile", "Brewfile", "Procfile", "Vagrantfile",
        "Jenkinsfile"
    );

    public static final Set<String> CODE_SKIPPED_FOLDER_NAMES = Set.of(
        "vendor", "Vendor", "vendored", "third_party", "third-party", "thirdparty", "3rdparty",
        "external", "extern", "deps", "build", "Build", "dist", "out", "target", "obj",
        "coverage", "htmlcov", "generated", "__generated__", "wandb", "mlruns", "lightning_logs"
    );

    public static final List<String> SCREENSHOT_PREFIXES = List.of(
        "Screenshot", "Screen Shot", "CleanShot", "SCR-", "Snip", "Capture"
    );

    public static class Classification {
        public enum Type { INDEX, SKIP, IGNORE }
        public final Type type;
        public final FileKind kind;
        public final String reason;

        public Classification(Type type, FileKind kind, String reason) {
            this.type = type;
            this.kind = kind;
            this.reason = reason;
        }

        public static Classification index(FileKind kind) {
            return new Classification(Type.INDEX, kind, null);
        }

        public static Classification skip(String reason) {
            return new Classification(Type.SKIP, null, reason);
        }

        public static Classification ignore() {
            return new Classification(Type.IGNORE, null, null);
        }
    }

    public static boolean isSecret(String name) {
        String lower = name.toLowerCase();
        int extDot = lower.lastIndexOf('.');
        String ext = extDot >= 0 ? lower.substring(extDot + 1) : "";
        Set<String> secretExts = Set.of("pem", "key", "p12", "pfx", "keystore", "jks", "ppk", "kdbx", "gpg", "asc", "crt", "cer", "der", "p8", "mobileprovision");
        if (secretExts.contains(ext)) return true;

        List<String> secretPrefixes = List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", ".env", "secret", "credential", ".netrc", ".npmrc", ".pypirc", "htpasswd", ".htpasswd");
        for (String sp : secretPrefixes) {
            if (lower.startsWith(sp)) return true;
        }
        return false;
    }

    public static boolean isProjectRoot(File dir) {
        if (!dir.isDirectory()) return false;
        for (String m : PROJECT_MARKERS) {
            if (new File(dir, m).exists()) return true;
        }
        return false;
    }

    public static Classification classify(File file, IndexOptions options) {
        String name = file.getName();
        String lowerName = name.toLowerCase();
        int extDot = lowerName.lastIndexOf('.');
        String ext = extDot >= 0 ? lowerName.substring(extDot + 1) : "";

        if (options.excludedExtensions.contains(ext)) return Classification.skip("excluded extension");
        if (isSecret(name)) return Classification.skip("key or credential file");

        // Screenshot check
        if (IMAGE_EXTENSIONS.contains(ext)) {
            for (String sp : SCREENSHOT_PREFIXES) {
                if (name.startsWith(sp)) {
                    return Classification.index(FileKind.screenshot);
                }
            }
            return Classification.index(FileKind.image);
        }

        if (AUDIO_EXTENSIONS.contains(ext)) return Classification.index(FileKind.audio);
        if (VIDEO_EXTENSIONS.contains(ext)) return Classification.index(FileKind.video);
        if ("pdf".equals(ext)) return Classification.index(FileKind.pdf);
        if (DOC_EXTENSIONS.contains(ext)) return Classification.index(FileKind.doc);

        return Classification.ignore();
    }

    public static Classification classifyCode(File file, IndexOptions options) {
        String name = file.getName();
        String lowerName = name.toLowerCase();
        int extDot = lowerName.lastIndexOf('.');
        String ext = extDot >= 0 ? lowerName.substring(extDot + 1) : "";

        if (options.excludedExtensions.contains(ext)) return Classification.skip("excluded extension");
        if (isSecret(name)) return Classification.skip("key or credential file");

        if (lowerName.endsWith(".min.js") || lowerName.endsWith(".min.css")) {
            return Classification.skip("minified");
        }
        if (lowerName.startsWith("license") || lowerName.startsWith("licence") || lowerName.startsWith("notice")) {
            return Classification.skip("license");
        }

        if (SOURCE_EXTENSIONS.contains(ext) || CODE_TEXT_EXTENSIONS.contains(ext) || "ipynb".equals(ext)
            || CODE_FILE_NAMES.contains(name) || name.startsWith("Dockerfile")) {
            return Classification.index(FileKind.code);
        }

        return Classification.ignore();
    }

    public static String skipReasonForFolder(File folder, IndexOptions options) {
        String name = folder.getName();
        if (SKIPPED_FOLDER_NAMES.contains(name)) return "standard skip folder";
        if (options.excludedFolders.contains(folder.getAbsolutePath())) return "user excluded folder";
        return null;
    }

    public static String codeSkipReasonForFolder(File folder) {
        String name = folder.getName();
        if (CODE_SKIPPED_FOLDER_NAMES.contains(name)) return "vendored or built code";
        return null;
    }
}
