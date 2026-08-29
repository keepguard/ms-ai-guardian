package com.keepguard.ms_ai_guardian.domain.classification;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ClassificationEngine {

    private ClassificationEngine() {}

    public static BusinessVerdict evaluate(List<ClassificationRule> rules, String errorReason, String logs) {
        String errorLower = errorReason != null ? errorReason.toLowerCase(Locale.ROOT) : "";
        String logsLower = logs != null ? logs.toLowerCase(Locale.ROOT) : "";
        List<ClassificationRule> ordered = rules.stream()
                .filter(ClassificationRule::enabled)
                .sorted(Comparator.comparingInt(ClassificationRule::priority))
                .toList();
        Map<String, String> vars = Map.of("errorReason", errorReason != null ? errorReason : "");
        for (ClassificationRule rule : ordered) {
            if (rule.matches(errorLower, logsLower)) {
                return new BusinessVerdict(
                        rule.verdict(),
                        render(rule.summaryTemplate(), vars),
                        render(rule.explanationTemplate(), vars),
                        render(rule.suggestedActionTemplate(), vars),
                        rule.requiresCodePr()
                );
            }
        }
        return BusinessVerdict.codeDefect(errorReason);
    }

    static String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (var entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }
}
