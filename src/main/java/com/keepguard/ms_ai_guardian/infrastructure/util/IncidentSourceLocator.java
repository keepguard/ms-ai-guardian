package com.keepguard.ms_ai_guardian.infrastructure.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai arquivo e linha do incidente a partir de logs/stack — sem nomes de serviço chumbados.
 * A linha fica amarrada ao mesmo arquivo escolhido; frames de runtime/framework são ignorados.
 */
public final class IncidentSourceLocator {

    private static final Pattern FILE_LINE = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]+\\.(?:go|java|kt|kts|ts|js|py)):(\\d+)");
    private static final Pattern JAVA_STACK = Pattern.compile(
            "\\(([A-Za-z0-9_]+\\.java):(\\d+)\\)");
    private static final Pattern ZAP_CALLER = Pattern.compile(
            "\"caller\"\\s*:\\s*\"([^\"]+\\.(?:go|java|kt)):(\\d+)\"");

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

        public String primaryBasename() {
            return basenames.isEmpty() ? "" : basenames.get(0);
        }
    }

    private record Frame(String path, int line, int position, boolean zapCaller) {}

    public static Hint parse(String logs, String errorReason) {
        String blob = (logs == null ? "" : logs) + "\n" + (errorReason == null ? "" : errorReason);
        List<Frame> frames = collectFrames(blob);
        Frame primary = pickPrimary(frames);

        Set<String> paths = new LinkedHashSet<>();
        Set<String> bases = new LinkedHashSet<>();
        Integer line = null;
        if (primary != null) {
            paths.add(primary.path());
            bases.add(basename(primary.path()));
            line = primary.line();
        }
        for (Frame f : frames) {
            paths.add(f.path());
            bases.add(basename(f.path()));
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
        if (hint != null && !hint.basenames().isEmpty()) {
            String primary = hint.primaryBasename();
            if (base.equalsIgnoreCase(primary)) {
                score += 200;
            } else {
                for (int i = 1; i < hint.basenames().size(); i++) {
                    if (base.equalsIgnoreCase(hint.basenames().get(i))) {
                        score += 25;
                    }
                }
            }
            for (String rel : hint.relativePaths()) {
                String r = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!r.isBlank() && lower.endsWith(r) && basename(r).equalsIgnoreCase(primary)) {
                    score += 40;
                }
            }
        }
        for (String token : errorHaystack.split("[^a-z0-9_]+")) {
            if (token.length() >= 4 && !isWeakErrorToken(token) && lower.contains(token)) {
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

    static boolean isFrameworkFrame(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = basename(lower);
        if (lower.contains("http:") || lower.contains("https:")) {
            return true;
        }
        if (lower.contains("/echo/v4") || lower.contains("labstack/echo")
                || lower.contains("/gin-gonic/") || lower.contains("/go-chi/")
                || lower.contains("/gofiber/") || lower.contains("gorilla/mux")) {
            return true;
        }
        if (lower.contains("/runtime/") || lower.contains("/net/http/")
                || lower.contains("/internal/poll/") || lower.contains("/database/sql/")
                || lower.contains("go/pkg/mod/")) {
            return true;
        }
        if (lower.contains("middleware/recover.go") || lower.contains("middleware/cors.go")) {
            return true;
        }
        if (base.equals("echo.go") || base.equals("recover.go") || base.equals("cors.go")
                || base.equals("panic.go") || base.equals("netpoll.go") || base.equals("fd_unix.go")
                || base.equals("fd_poll_runtime.go")) {
            return true;
        }
        if (base.equals("httpservlet.java") || base.equals("dispatcherservlet.java")
                || base.equals("thread.java") || base.equals("filterchain.java")) {
            return true;
        }
        return false;
    }

    static boolean isWeakErrorToken(String token) {
        return Set.of("mock", "gateway", "service", "health", "http", "json", "error",
                "incidente", "microsservico", "keepguard", "company", "status", "database",
                "telecom", "internal", "handler").contains(token);
    }

    public static boolean lineExistsIn(String source, int lineNumber) {
        if (source == null || lineNumber < 1) {
            return false;
        }
        int seen = 1;
        for (int i = 0; i < source.length(); i++) {
            if (seen == lineNumber) {
                return true;
            }
            if (source.charAt(i) == '\n') {
                seen++;
            }
        }
        return seen == lineNumber;
    }

    private static List<Frame> collectFrames(String blob) {
        List<Frame> frames = new ArrayList<>();
        Matcher zap = ZAP_CALLER.matcher(blob);
        while (zap.find()) {
            String file = zap.group(1).replace('\\', '/');
            if (isFrameworkFrame(file)) {
                continue;
            }
            frames.add(new Frame(file, Integer.parseInt(zap.group(2)), zap.start(), true));
        }
        Matcher java = JAVA_STACK.matcher(blob);
        while (java.find()) {
            String file = java.group(1).replace('\\', '/');
            if (isFrameworkFrame(file)) {
                continue;
            }
            frames.add(new Frame(file, Integer.parseInt(java.group(2)), java.start(), false));
        }
        Matcher m = FILE_LINE.matcher(blob);
        while (m.find()) {
            String file = m.group(1).replace('\\', '/');
            if (isFrameworkFrame(file)) {
                continue;
            }
            frames.add(new Frame(file, Integer.parseInt(m.group(2)), m.start(), false));
        }
        return frames;
    }

    private static Frame pickPrimary(List<Frame> frames) {
        if (frames.isEmpty()) {
            return null;
        }
        return frames.stream()
                .max(Comparator
                        .comparing((Frame f) -> f.zapCaller() ? 1 : 0)
                        .thenComparingInt(Frame::position))
                .orElse(frames.get(frames.size() - 1));
    }
}
