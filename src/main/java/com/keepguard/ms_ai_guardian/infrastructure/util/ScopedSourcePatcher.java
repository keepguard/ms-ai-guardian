package com.keepguard.ms_ai_guardian.infrastructure.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recorta o arquivo-fonte ao método/função do incidente e reinsere só esse trecho,
 * para o CoderAgent não reescrever a classe inteira.
 */
public final class ScopedSourcePatcher {

    private static final Pattern GO_FUNC = Pattern.compile("(?m)^func\\s");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(public|protected|private|static|final|synchronized|native|abstract|[\\w.<>,\\[\\]]+)+\\s+\\w+\\s*\\(");

    private ScopedSourcePatcher() {}

    public record Slice(String fullSource, String functionSource, String language) {
        public boolean isWholeFile() {
            return functionSource == null || functionSource.equals(fullSource);
        }
    }

    public static Slice extract(String source, String filePath, String errorReason, String rootCause) {
        return extract(source, filePath, errorReason, rootCause, null);
    }

    public static Slice extract(String source, String filePath, String errorReason, String rootCause,
            Integer lineNumber) {
        if (source == null || source.isBlank()) {
            return new Slice("", "", "unknown");
        }
        String lang = languageOf(filePath);
        List<String> functions = "go".equals(lang) ? splitGoFunctions(source)
                : "java".equals(lang) ? splitJavaMethods(source) : List.of(source);
        if (functions.isEmpty() || (functions.size() == 1 && functions.get(0).equals(source))) {
            return new Slice(source, source, lang);
        }
        if (lineNumber != null && lineNumber > 0) {
            String byLine = functionContainingLine(source, functions, lineNumber);
            if (byLine != null) {
                return new Slice(source, byLine, lang);
            }
        }
        String haystack = ((errorReason == null ? "" : errorReason) + " "
                + (rootCause == null ? "" : rootCause)).toLowerCase(Locale.ROOT);
        String best = pickFunction(functions, haystack);
        return new Slice(source, best, lang);
    }

    public static String applyReplacement(Slice slice, String llmOutput) {
        if (slice == null || slice.fullSource() == null || slice.fullSource().isBlank()) {
            return llmOutput == null ? "" : stripMarkdown(llmOutput);
        }
        String cleaned = stripMarkdown(llmOutput);
        if (cleaned == null || cleaned.isBlank()) {
            return slice.fullSource();
        }
        if (slice.isWholeFile()) {
            return cleaned;
        }
        String replacement = isolateFunction(cleaned, slice.functionSource(), slice.language());
        if (replacement == null || replacement.isBlank()) {
            return slice.fullSource();
        }
        int idx = slice.fullSource().indexOf(slice.functionSource());
        if (idx < 0) {
            return slice.fullSource();
        }
        return slice.fullSource().substring(0, idx)
                + ensureTrailingNewline(replacement)
                + slice.fullSource().substring(idx + slice.functionSource().length());
    }

    static String stripMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = s.replaceAll("^```[a-zA-Z]*\\s*", "");
        s = s.replaceAll("```\\s*$", "");
        return s.trim();
    }

    static List<String> splitGoFunctions(String source) {
        List<Integer> starts = new ArrayList<>();
        Matcher m = GO_FUNC.matcher(source);
        while (m.find()) {
            starts.add(m.start());
        }
        if (starts.isEmpty()) {
            return List.of(source);
        }
        List<String> parts = new ArrayList<>();
        // preamble (package, imports, types) is not a function — keep attached to first func
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : source.length();
            parts.add(source.substring(from, to));
        }
        return parts;
    }

    static List<String> splitJavaMethods(String source) {
        List<Integer> starts = new ArrayList<>();
        Matcher m = JAVA_METHOD.matcher(source);
        while (m.find()) {
            String line = source.substring(m.start(), Math.min(source.length(), m.start() + 80));
            if (line.contains(" class ") || line.contains(" interface ") || line.contains(" enum ")) {
                continue;
            }
            starts.add(m.start());
        }
        if (starts.size() < 2) {
            return List.of(source);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : source.length();
            parts.add(source.substring(from, to));
        }
        return parts;
    }

    static String pickFunction(List<String> functions, String haystack) {
        String best = functions.get(0);
        int bestScore = -1;
        for (String fn : functions) {
            int score = score(fn, haystack);
            if (score > bestScore) {
                bestScore = score;
                best = fn;
            }
        }
        return best;
    }

    static int score(String function, String haystack) {
        String fn = function.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : haystack.split("[^a-z0-9_]+")) {
            if (token.length() < 4) {
                continue;
            }
            if (fn.contains(token)) {
                score += 3;
            }
        }
        return score;
    }

    static String functionContainingLine(String source, List<String> functions, int lineNumber) {
        int offset = offsetOfLine(source, lineNumber);
        if (offset < 0) {
            return null;
        }
        String best = null;
        int bestStart = -1;
        for (String fn : functions) {
            int idx = source.indexOf(fn);
            if (idx >= 0 && offset >= idx && offset < idx + fn.length() && idx >= bestStart) {
                best = fn;
                bestStart = idx;
            }
        }
        return best;
    }

    static int offsetOfLine(String source, int lineNumber) {
        if (lineNumber <= 1) {
            return 0;
        }
        int seen = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                seen++;
                if (seen == lineNumber) {
                    return Math.min(i + 1, source.length() - 1);
                }
            }
        }
        return -1;
    }

    static String isolateFunction(String llmOutput, String originalFunction, String language) {
        if (llmOutput.contains(firstLine(originalFunction).trim()) && !looksLikeFullFile(llmOutput, language)) {
            return llmOutput;
        }
        List<String> fromLlm = "go".equals(language) ? splitGoFunctions(llmOutput) : splitJavaMethods(llmOutput);
        String sig = signatureHint(originalFunction);
        for (String fn : fromLlm) {
            if (sig != null && fn.contains(sig)) {
                return fn;
            }
        }
        if (!looksLikeFullFile(llmOutput, language)) {
            return llmOutput;
        }
        return fromLlm.stream()
                .filter(fn -> fn.contains(sig != null ? sig : firstLine(originalFunction)))
                .findFirst()
                .orElse(null);
    }

    static boolean looksLikeFullFile(String text, String language) {
        String head = text.lines().limit(8).reduce("", (a, b) -> a + "\n" + b).toLowerCase(Locale.ROOT);
        if ("go".equals(language)) {
            return head.contains("package ") && head.contains("func ");
        }
        return head.contains("package ") && (head.contains("class ") || head.contains("interface "));
    }

    static String signatureHint(String function) {
        String first = firstLine(function).trim();
        Matcher go = Pattern.compile("^func\\s+(?:\\([^)]*\\)\\s+)?(\\w+)\\s*\\(").matcher(first);
        if (go.find()) {
            return go.group(1);
        }
        return first.length() > 12 ? first.substring(0, Math.min(40, first.length())) : first;
    }

    static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    public static String languageOf(String filePath) {
        if (filePath == null) {
            return "unknown";
        }
        String p = filePath.toLowerCase(Locale.ROOT);
        if (p.endsWith(".go")) {
            return "go";
        }
        if (p.endsWith(".java")) {
            return "java";
        }
        return "unknown";
    }

    static String ensureTrailingNewline(String s) {
        return s.endsWith("\n") ? s : s + "\n";
    }
}
