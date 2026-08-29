package com.keepguard.ms_ai_guardian.infrastructure.template;

import java.util.Map;

public final class PlaceholderRenderer {

    private PlaceholderRenderer() {}

    public static String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String out = template;
        for (var entry : variables.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            out = out.replace("{{" + entry.getKey() + "}}", value);
        }
        return out;
    }

    public static String nvl(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    public static String html(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>");
    }
}
