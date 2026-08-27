package com.keepguard.ms_ai_guardian.infrastructure.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai arquivo e linha do incidente a partir de logs/stack — sem nomes de serviço chumbados.
 */
public final class IncidentSourceLocator {

    private static final Pattern FILE_LINE = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]+\\.(?:go|java|kt|kts|ts|js|py)):(\\d+)");
    private static final Pattern JAVA_STACK = Pattern.compile(
            "\\(([A-Za-z0-9_]+\\.java):(\\d+)\\)");

    private IncidentSourceLocator() {}

    public record Hint(List<String> relativePaths, List<String> basenames, Integer lineNumber) {
        public String fingerprintLocation() {
            if (!basenames.isEmpty() && lineNumber != null) {
                return basenames.get(0) + ":" + lineNumber;
            }
            if (!basenames.isEmpty()) {
                return basenames.get(0);
            }
            return "general";
        }
    }

    public static Hint parse(String logs, String errorReason) {
        String blob = (logs == null ? "" : logs) + "\n" + (errorReason == null ? "" : errorReason);
        Set<String> paths = new LinkedHashSet<>();
        Set<String> bases = new LinkedHashSet<>();
        Integer line = null;

        Matcher java = JAVA_STACK.matcher(blob);
        while (java.find()) {
            String file = java.group(1).replace('\\', '/');
            bases.add(basename(file));
            paths.add(file);
            if (line == null) {
                line = Integer.parseInt(java.group(2));
            }
        }

        Matcher m = FILE_LINE.matcher(blob);
        while (m.find()) {
            String file = m.group(1).replace('\\', '/');
            if (file.contains("http:") || file.contains("https:")) {
                continue;
            }
            paths.add(file);
            bases.add(basename(file));
            if (line == null) {
                line = Integer.parseInt(m.group(2));
            }
        }

        return new Hint(List.copyOf(paths), List.copyOf(bases), line);
    }

    public static Optional<String> fingerprintLocation(String logs, String errorReason) {
        Hint hint = parse(logs, errorReason);
        String loc = hint.fingerprintLocation();
        return "general".equals(loc) ? Optional.empty() : Optional.of(loc);
    }

    public static List<String> rankPaths(List<String> treePaths, Hint hint, String errorReason) {
        if (treePaths == null || treePaths.isEmpty()) {
            return List.of();
        }
        String haystack = (errorReason == null ? "" : errorReason).toLowerCase(Locale.ROOT);
        List<String> scored = new ArrayList<>(treePaths);
        scored.sort((a, b) -> Integer.compare(
                pathScore(b, hint, haystack),
                pathScore(a, hint, haystack)));
        return scored.stream()
                .filter(p -> pathScore(p, hint, haystack) > 0)
                .limit(8)
                .toList();
    }

    static int pathScore(String path, Hint hint, String errorHaystack) {
        String p = path.replace('\\', '/');
        String lower = p.toLowerCase(Locale.ROOT);
        String base = basename(p).toLowerCase(Locale.ROOT);
        int score = 0;
        if (isTestFile(p)) {
            score -= 8;
        }
        if (hint != null) {
            for (String b : hint.basenames()) {
                if (base.equalsIgnoreCase(b)) {
                    score += 100;
                } else if (lower.endsWith("/" + b.toLowerCase(Locale.ROOT))) {
                    score += 90;
                }
            }
            for (String rel : hint.relativePaths()) {
                if (lower.endsWith(rel.toLowerCase(Locale.ROOT))) {
                    score += 40;
                }
            }
        }
        for (String token : errorHaystack.split("[^a-z0-9_]+")) {
            if (token.length() >= 4 && lower.contains(token)) {
                score += 4;
            }
        }
        return score;
    }

    static boolean isTestFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.endsWith("_test.go") || lower.endsWith("test.java")
                || lower.endsWith("_test.java");
    }

    static String basename(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
