package com.digup.engine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text normalization and snippet formatting for keyword and hybrid search.
 */
public final class SearchText {

    private SearchText() {}

    /**
     * NFKC normalized text with zero-width characters removed and Arabic/Indic diacritics normalized.
     */
    public static String normalized(String text) {
        if (text == null || text.isEmpty()) return "";
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());

        for (int i = 0; i < nfkc.length(); i++) {
            int codePoint = nfkc.codePointAt(i);
            if (Character.isSupplementaryCodePoint(codePoint)) {
                i++; // skip second surrogate
            }

            if (codePoint == 0x200B) {
                sb.append(' '); // zero-width space -> space
                continue;
            }
            if (codePoint == 0x00AD || codePoint == 0x061C ||
                (codePoint >= 0x200C && codePoint <= 0x200F) ||
                (codePoint >= 0x202A && codePoint <= 0x202E) ||
                (codePoint >= 0x2060 && codePoint <= 0x2064) ||
                (codePoint >= 0x2066 && codePoint <= 0x2069) ||
                codePoint == 0xFEFF) {
                continue; // joiners, direction marks, soft hyphen, BOM
            }
            if (codePoint == 0x0640 || (codePoint >= 0x064B && codePoint <= 0x065F) || codePoint == 0x0670) {
                continue; // tatweel, harakat, dagger alef
            }
            if (codePoint == 0x0622 || codePoint == 0x0623 || codePoint == 0x0625 || codePoint == 0x0671) {
                sb.append('\u0627'); // alef variants -> plain alef
                continue;
            }

            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }

    /**
     * True for words of scripts that attach endings to words (Indic/Bengali or Arabic).
     */
    public static boolean inflects(String word) {
        if (word == null) return false;
        for (int i = 0; i < word.length(); i++) {
            int cp = word.codePointAt(i);
            if ((cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0900 && cp <= 0x0D7F)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether text says something in words: at least a fifth of non-whitespace is letters.
     */
    public static boolean hasWords(String text) {
        if (text == null || text.isEmpty()) return false;
        int letters = 0;
        int others = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (Character.isLetter(c)) {
                letters++;
            } else {
                others++;
            }
        }
        return letters > 0 && letters * 4 >= others;
    }

    public static class Range {
        public final int start;
        public final int end;
        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Finds matches of term in text as whole word (or prefix if inflecting).
     */
    public static List<Range> matches(String term, String text) {
        if (term == null || term.isEmpty() || text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String cleanTerm = normalized(term).trim().toLowerCase();
        if (cleanTerm.isEmpty()) return Collections.emptyList();

        List<Range> list = new ArrayList<>();
        String cleanText = normalized(text).toLowerCase();
        boolean prefix = inflects(term);

        int index = 0;
        while (index < cleanText.length()) {
            int pos = cleanText.indexOf(cleanTerm, index);
            if (pos < 0) break;

            int endPos = pos + cleanTerm.length();
            boolean validStart = (pos == 0) || !Character.isLetterOrDigit(cleanText.charAt(pos - 1));
            boolean validEnd = (endPos >= cleanText.length()) || !Character.isLetterOrDigit(cleanText.charAt(endPos));

            if (validStart && (validEnd || prefix)) {
                int finalEnd = endPos;
                if (prefix && !validEnd) {
                    while (finalEnd < cleanText.length() && Character.isLetterOrDigit(cleanText.charAt(finalEnd))) {
                        finalEnd++;
                    }
                }
                list.add(new Range(pos, finalEnd));
                index = finalEnd;
            } else {
                index = pos + 1;
            }
        }
        return list;
    }

    /**
     * One line from text around the first term match, marked with « » snippet delimiters.
     */
    public static String snippet(String text, List<String> terms, int words) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) return null;

        List<Range> allRanges = new ArrayList<>();
        for (String t : terms) {
            allRanges.addAll(matches(t, text));
        }
        if (allRanges.isEmpty()) return null;

        allRanges.sort(Comparator.comparingInt(r -> r.start));
        Range first = allRanges.get(0);

        // Find window around first
        int start = first.start;
        int wordsBefore = 0;
        while (start > 0 && wordsBefore < words / 3) {
            start--;
            if (Character.isWhitespace(text.charAt(start)) && start < first.start && !Character.isWhitespace(text.charAt(start + 1))) {
                wordsBefore++;
            }
        }
        if (start > 0 || Character.isWhitespace(text.charAt(start))) {
            start = Math.min(start + 1, text.length());
        }

        int end = first.end;
        int wordsAfter = 0;
        while (end < text.length() && wordsAfter < (words - wordsBefore)) {
            if (Character.isWhitespace(text.charAt(end))) {
                wordsAfter++;
            }
            end++;
        }

        StringBuilder out = new StringBuilder();
        if (start > 0) out.append("…");

        int cursor = start;
        for (Range r : allRanges) {
            if (r.start >= start && r.end <= end && r.start >= cursor) {
                out.append(text, cursor, r.start);
                out.append("«").append(text, r.start, r.end).append("»");
                cursor = r.end;
            }
        }
        if (cursor < end && cursor <= text.length()) {
            out.append(text, cursor, end);
        }
        if (end < text.length()) out.append("…");
        return out.toString().replaceAll("[\\r\\n]+", " ").trim();
    }
}
