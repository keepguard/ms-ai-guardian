package com.keepguard.ms_ai_guardian.application.port.out.llm;

import java.util.Map;

public interface PromptCatalogPort {

    String render(String key, Map<String, String> variables);

    PromptSnapshot snapshot(String key);

    record PromptSnapshot(String key, String version, String body) {}
}
