package com.digup.engine;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Symbol identifier identification and definition lookup for code navigation.
 */
public final class CodeLookup {

    private static final Pattern IDENT_REGEX = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(?:(?:\\.|::|->|#)[A-Za-z_$][A-Za-z0-9_$]*)*$");

    private static final Set<String> DEFINERS = Set.of(
        "def", "func", "fn", "fun", "function", "class", "struct", "enum", "protocol",
        "interface", "trait", "type", "typealias", "actor", "extension", "impl", "module",
        "namespace", "object", "record", "macro", "const", "let", "var", "val", "union",
        "mod", "sub", "@interface", "@implementation", "@protocol", "#define", "function*"
    );

    private static final Set<String> USERS = Set.of(
        "return", "await", "yield", "throw", "new", "else", "case", "print", "echo",
        "assert", "not", "and", "or", "in", "is", "do", "go", "defer", "try", "catch",
        "if", "while", "for", "typeof", "delete", "raise", "import", "from"
    );

    private static final List<String> COMMENT_STARTS = List.of(
        "//", "#", "/*", "*", "--", "\"\"\"", "'''", "<!--", ";"
    );

    private CodeLookup() {}

    /**
     * Splits code identifier into words, or returns null if not an identifier.
     */
    public static List<String> identifier(String query) {
        if (query == null) return null;
        String name = query.trim();
        if (name.endsWith("()")) {
            name = name.substring(0, name.length() - 2);
        }
        if (!IDENT_REGEX.matcher(name).matches()) return null;

        boolean hasUpper = false, hasLower = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) hasUpper = true;
            if (Character.isLowerCase(c)) hasLower = true;
        }
        boolean camel = hasUpper && hasLower;
        boolean hasSpecial = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ("_.:#$".indexOf(c) >= 0 || Character.isDigit(c)) {
                hasSpecial = true;
                break;
            }
        }
        if (!camel && !hasSpecial) return null;

        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] parts = spaced.toLowerCase().split("[^a-zA-Z0-9]+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) list.add(p);
        }
        return list.isEmpty() ? null : list;
    }

    public static String phrase(List<String> words) {
        return "\"" + String.join(" ", words) + "\"";
    }

    public static class DefMatch {
        public final int line;
        public final int strength;
        public DefMatch(int line, int strength) {
            this.line = line;
            this.strength = strength;
        }
    }

    public static DefMatch definition(String name, String text, int firstLine) {
        String[] lines = text.split("\\r?\\n");
        DefMatch best = null;

        for (int offset = 0; offset < lines.length; offset++) {
            String line = lines[offset];
            List<int[]> occurrences = findOccurrences(name, line);
            for (int[] occ : occurrences) {
                String before = line.substring(0, occ[0]).trim();
                String after = line.substring(occ[1]).trim();
                int strength = definitionStrength(before, after, line);
                if (best == null || strength > best.strength) {
                    best = new DefMatch(firstLine + offset, strength);
                }
                if (strength == 2) return best;
            }
        }
        return best;
    }

    private static int definitionStrength(String before, String after, String line) {
        String[] parts = before.split("\\s+");
        String previous = parts.length > 0 ? parts[parts.length - 1] : "";
        if (DEFINERS.contains(previous)) return 2;
        if (before.startsWith("func (") && before.endsWith(")")) return 2; // Go method
        if (before.startsWith("- (") || before.startsWith("+ (")) {
            return (after.startsWith(":") || after.isEmpty() || after.startsWith("{") || after.startsWith(";")) ? 2 : 0;
        }
        if (after.startsWith("(") && !before.isEmpty() && !USERS.contains(previous)
            && !before.matches(".*[=(,.!?].*") && !before.contains("->")) {
            char last = before.charAt(before.length() - 1);
            if (Character.isLetterOrDigit(last) || "*&>_]".indexOf(last) >= 0) {
                return line.trim().endsWith(";") ? 1 : 2;
            }
        }
        return 0;
    }

    public static Integer firstLine(List<String> words, String text, int firstLine) {
        List<String> cleanWords = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 2) cleanWords.add(w.toLowerCase());
        }
        if (cleanWords.isEmpty()) return null;

        String[] lines = text.split("\\r?\\n");
        Integer firstCode = null;
        Integer firstAny = null;

        for (int offset = 0; offset < lines.length; offset++) {
            String line = lines[offset];
            boolean contains = false;
            for (String w : cleanWords) {
                if (hasWord(w, line)) {
                    contains = true;
                    break;
                }
            }
            if (contains) {
                String trimmed = line.trim();
                String[] wordsInLine = trimmed.split("\\s+");
                for (int i = 0; i < Math.min(4, wordsInLine.length); i++) {
                    if (DEFINERS.contains(wordsInLine[i])) {
                        return firstLine + offset;
                    }
                }
                boolean isComment = false;
                for (String cs : COMMENT_STARTS) {
                    if (trimmed.startsWith(cs)) {
                        isComment = true;
                        break;
                    }
                }
                if (firstCode == null && !isComment) {
                    firstCode = offset;
                }
                if (firstAny == null) {
                    firstAny = offset;
                }
            }
        }
        int chosen = firstCode != null ? firstCode : (firstAny != null ? firstAny : -1);
        return chosen >= 0 ? firstLine + chosen : null;
    }

    private static boolean hasWord(String word, String line) {
        String lower = line.toLowerCase();
        int idx = 0;
        while ((idx = lower.indexOf(word, idx)) >= 0) {
            boolean startOk = (idx == 0) || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            if (startOk) return true;
            idx += word.length();
        }
        return false;
    }

    private static List<int[]> findOccurrences(String name, String line) {
        List<int[]> res = new ArrayList<>();
        String lowerLine = line.toLowerCase();
        String lowerName = name.toLowerCase();
        int idx = 0;
        while ((idx = lowerLine.indexOf(lowerName, idx)) >= 0) {
            int end = idx + lowerName.length();
            boolean clearBefore = (idx == 0) || !isNameChar(line.charAt(idx - 1));
            boolean clearAfter = (end == line.length()) || !isNameChar(line.charAt(end));
            if (clearBefore && clearAfter) {
                res.add(new int[]{idx, end});
            }
            idx = end;
        }
        return res;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
