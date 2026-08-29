package com.keepguard.ms_ai_guardian.application.port.out.llm;

import java.util.Optional;
import java.util.UUID;

public interface LlmPort {

    boolean available();

    Optional<String> complete(LlmRequest request);

    record LlmRequest(
            String prompt,
            int timeoutSeconds,
            String promptKey,
            String promptVersion,
            UUID incidentId
    ) {
        public static LlmRequest of(String prompt, int timeoutSeconds, String promptKey) {
            return new LlmRequest(prompt, timeoutSeconds, promptKey, "classpath", null);
        }
    }
}
