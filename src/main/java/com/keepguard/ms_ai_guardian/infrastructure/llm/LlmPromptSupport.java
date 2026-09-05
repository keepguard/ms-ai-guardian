package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.infrastructure.i18n.GuardianPortuguese;

final class LlmPromptSupport {

    private LlmPromptSupport() {}

    static String withPortugueseNarrativeRule(LlmPort.LlmRequest request) {
        String prompt = request.prompt() != null ? request.prompt() : "";
        if (!isNarrativePrompt(request.promptKey())) {
            return prompt;
        }
        if (prompt.contains("português brasileiro") || prompt.contains("Idioma obrigatório")) {
            return prompt;
        }
        return GuardianPortuguese.NARRATIVE_LANGUAGE_RULE + "\n" + prompt;
    }

    static boolean isNarrativePrompt(String promptKey) {
        return PromptKeys.SRE_INVESTIGATE.equals(promptKey)
                || PromptKeys.REVIEWER_HOTFIX_SCOPE.equals(promptKey);
    }
}
