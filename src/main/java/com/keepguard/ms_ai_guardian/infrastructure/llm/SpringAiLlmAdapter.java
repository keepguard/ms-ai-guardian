package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import com.keepguard.ms_ai_guardian.domain.repository.LlmInvocationRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.i18n.GuardianPortuguese;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiLlmAdapter implements LlmPort {

    private static final String TIMEOUT_SENTINEL = "__LLM_TIMEOUT_OR_EMPTY__";

    private final Optional<ChatClient.Builder> chatClientBuilder;
    private final GuardianLlmProperties llmProperties;
    private final LlmInvocationRepository invocationRepository;

    @Override
    public boolean available() {
        return chatClientBuilder.isPresent() && llmProperties.isEnabled();
    }

    @Override
    public Optional<String> complete(LlmRequest request) {
        if (!available()) {
            record(request, null, 0, true);
            return Optional.empty();
        }
        long started = System.currentTimeMillis();
        try {
            String prompt = withPortugueseNarrativeRule(request);
            String raw = LlmContextLimiter.callWithTimeout(
                    () -> chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content(),
                    request.timeoutSeconds(),
                    TIMEOUT_SENTINEL);
            long latency = System.currentTimeMillis() - started;
            if (raw == null || raw.isBlank() || TIMEOUT_SENTINEL.equals(raw)) {
                record(request, null, latency, true);
                return Optional.empty();
            }
            record(request, raw, latency, false);
            return Optional.of(raw);
        } catch (Exception e) {
            log.warn("LLM falhou ({}): {}", request.promptKey(), e.getMessage());
            record(request, null, System.currentTimeMillis() - started, true);
            return Optional.empty();
        }
    }

    private static String withPortugueseNarrativeRule(LlmRequest request) {
        String prompt = request.prompt() != null ? request.prompt() : "";
        if (!isNarrativePrompt(request.promptKey())) {
            return prompt;
        }
        if (prompt.contains("português brasileiro") || prompt.contains("Idioma obrigatório")) {
            return prompt;
        }
        return GuardianPortuguese.NARRATIVE_LANGUAGE_RULE + "\n" + prompt;
    }

    private static boolean isNarrativePrompt(String promptKey) {
        return PromptKeys.SRE_INVESTIGATE.equals(promptKey)
                || PromptKeys.REVIEWER_HOTFIX_SCOPE.equals(promptKey);
    }

    private void record(LlmRequest request, String output, long latencyMs, boolean fallback) {
        try {
            String hashed = withPortugueseNarrativeRule(request);
            invocationRepository.save(LlmInvocation.builder()
                    .incidentId(request.incidentId())
                    .promptKey(request.promptKey())
                    .promptVersion(request.promptVersion())
                    .model(llmProperties.getProvider())
                    .inputHash(DigestUtils.md5DigestAsHex(hashed.getBytes(StandardCharsets.UTF_8)))
                    .output(output != null && output.length() > 8000 ? output.substring(0, 8000) : output)
                    .latencyMs(latencyMs)
                    .fallbackUsed(fallback)
                    .build());
        } catch (Exception e) {
            log.debug("Não foi possível persistir invocação LLM: {}", e.getMessage());
        }
    }
}
