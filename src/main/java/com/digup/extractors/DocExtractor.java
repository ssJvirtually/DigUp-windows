package com.digup.extractors;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Text extractor and chunker for txt, md, html, docx, rtf.
 */
public final class DocExtractor {

    private DocExtractor() {}

    public static String extractText(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".docx")) {
            return extractDocx(file);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            return plainTextFromHtml(readRawText(file));
        } else if (name.endsWith(".rtf")) {
            return stripRtf(readRawText(file));
        } else {
            return readRawText(file);
        }
    }

    private static String readRawText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static String extractDocx(File file) {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Strip XML tags and preserve spaces
                        String text = line.replaceAll("<w:p[ >]", "\n")
                                          .replaceAll("<[^>]+>", "")
                                          .replaceAll("&amp;", "&")
                                          .replaceAll("&lt;", "<")
                                          .replaceAll("&gt;", ">")
                                          .replaceAll("&quot;", "\"")
                                          .replaceAll("&apos;", "'");
                        sb.append(text).append(" ");
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    public static String plainTextFromHtml(String html) {
        String text = html;
        text = text.replaceAll("(?is)<script\\b.*?</script\\s*>", " ");
        text = text.replaceAll("(?is)<style\\b.*?</style\\s*>", " ");
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = text.replaceAll("(?is)<head\\b.*?</head\\s*>", " ");
        text = text.replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)\\b[^>]*>", "\n");
        text = text.replaceAll("<[^>]+>", " ");

        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&apos;", "'")
                   .replace("&mdash;", "—")
                   .replace("&ndash;", "–")
                   .replace("&hellip;", "…");

        return text.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    private static String stripRtf(String rtf) {
        return rtf.replaceAll("\\\\[a-zA-Z0-9]+[ ]?", " ")
                  .replaceAll("[\\{\\}]", "")
                  .replaceAll("\\s+", " ")
                  .trim();
    }

    /**
     * Splits text into ~size-character pieces with overlap, breaking on whitespace.
     */
    public static List<String> chunks(String text, int size, int overlap, int limit) {
        String clean = text.replaceAll("[ \\t\\u00A0]+", " ")
                           .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                           .trim();

        List<String> result = new ArrayList<>();
        int len = clean.length();
        int start = 0;

        while (start < len && result.size() < limit) {
            int end = Math.min(start + size, len);
            if (end < len) {
                int lastSpace = clean.lastIndexOf(' ', end);
                if (lastSpace > start + size / 2) {
                    end = lastSpace;
                }
            }
            String chunk = clean.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            if (end >= len) break;

            int next = Math.max(start, end - overlap);
            while (next < end && !Character.isWhitespace(clean.charAt(next))) {
                next++;
            }
            start = (next < end) ? next : end;
        }
        return result;
    }
}
