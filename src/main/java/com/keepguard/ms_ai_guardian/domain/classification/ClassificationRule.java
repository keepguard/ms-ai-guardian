package com.keepguard.ms_ai_guardian.domain.classification;

import com.keepguard.ms_ai_guardian.domain.enums.ClassificationVerdict;

import java.util.List;

public record ClassificationRule(
        String id,
        int priority,
        ClassificationVerdict verdict,
        boolean requiresCodePr,
        List<String> errorContains,
        List<String> logsContains,
        String summaryTemplate,
        String explanationTemplate,
        String suggestedActionTemplate,
        boolean enabled
) {
    public boolean matches(String errorLower, String logsLower) {
        if (!enabled) {
            return false;
        }
        if (containsAny(errorLower, errorContains) || containsAny(logsLower, logsContains)) {
            return true;
        }
        return false;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        if (haystack == null || haystack.isBlank() || needles == null || needles.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
