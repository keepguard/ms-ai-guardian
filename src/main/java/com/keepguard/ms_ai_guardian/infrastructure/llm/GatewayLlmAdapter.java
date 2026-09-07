package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.keepguard.ms_ai_guardian.adapters.out.feign.LlmGatewayClient;
import com.keepguard.ms_ai_guardian.application.port.out.auth.AuthTokenPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.LlmInvocationRepositoryPort;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnExpression("!'${app.guardian.llm.provider:none}'.equalsIgnoreCase('ollama') && !'${app.guardian.llm.provider:none}'.equalsIgnoreCase('openai')")
public class GatewayLlmAdapter implements LlmPort {

    static final String SOURCE_SERVICE = "ms-ai-guardian";

    private final GuardianLlmProperties llmProperties;
    private final GuardianProperties guardianProperties;
    private final LlmInvocationRepositoryPort invocationRepository;
    private final AuthTokenPort authTokenPort;
    private final LlmGatewayClient llmGatewayClient;

    public GatewayLlmAdapter(
            GuardianLlmProperties llmProperties,
            GuardianProperties guardianProperties,
            LlmInvocationRepositoryPort invocationRepository,
            AuthTokenPort authTokenPort,
            LlmGatewayClient llmGatewayClient) {
        this.llmProperties = llmProperties;
        this.guardianProperties = guardianProperties;
        this.invocationRepository = invocationRepository;
        this.authTokenPort = authTokenPort;
        this.llmGatewayClient = llmGatewayClient;
    }

    @Override
    public boolean available() {
        return llmProperties.isEnabled() && StringUtils.hasText(llmProperties.getGatewayUrl());
    }

    @Override
    public Optional<String> complete(LlmRequest request) {
        if (!available()) {
            record(request, null, null, 0, true);
            return Optional.empty();
        }
        long started = System.currentTimeMillis();
        String prompt = LlmPromptSupport.withPortugueseNarrativeRule(request);
        try {
            GatewayLlmDtos.CompleteResponse response = callGateway(request, prompt);
            long latency = System.currentTimeMillis() - started;
            if (response == null || !StringUtils.hasText(response.content())) {
                record(request, null, null, latency, true);
                return Optional.empty();
            }
            record(request, response.content(), response.model(), latency, false);
            return Optional.of(response.content());
        } catch (Exception e) {
            log.warn("LLM gateway falhou ({}): {}", request.promptKey(), e.getMessage());
            record(request, null, null, System.currentTimeMillis() - started, true);
            return Optional.empty();
        }
    }

    private GatewayLlmDtos.CompleteResponse callGateway(LlmRequest request, String prompt) {
        String companyId = nvl(guardianProperties.getTenantId());
        String correlationId = request.incidentId() != null
                ? request.incidentId().toString()
                : UUID.randomUUID().toString();
        GatewayLlmDtos.CompleteRequest payload = new GatewayLlmDtos.CompleteRequest(
                blankToNull(llmProperties.getProviderId()),
                blankToNull(llmProperties.getModel()),
                List.of(new GatewayLlmDtos.Message("user", prompt)),
                llmProperties.getMaxTokens(),
                llmProperties.getTemperature(),
                request.promptKey(),
                companyId,
                correlationId,
                SOURCE_SERVICE
        );
        String authorization = bearer(companyId).map(token -> "Bearer " + token).orElse(null);
        try {
            return LlmContextLimiter.invokeWithTimeout(
                    () -> {
                        try {
                            return llmGatewayClient.complete(
                                    payload,
                                    StringUtils.hasText(companyId) ? companyId : null,
                                    correlationId,
                                    authorization);
                        } catch (Exception e) {
                            log.warn("LLM gateway HTTP falhou ({}): {}", request.promptKey(), e.getMessage());
                            return null;
                        }
                    },
                    request.timeoutSeconds(),
                    null);
        } catch (Exception e) {
            log.warn("LLM gateway HTTP falhou ({}): {}", request.promptKey(), e.getMessage());
            return null;
        }
    }

    private void record(LlmRequest request, String output, String model, long latencyMs, boolean fallback) {
        try {
            String hashed = LlmPromptSupport.withPortugueseNarrativeRule(request);
            invocationRepository.save(LlmInvocation.builder()
                    .incidentId(request.incidentId())
                    .promptKey(request.promptKey())
                    .promptVersion(request.promptVersion())
                    .model(model != null ? model : "gateway")
                    .inputHash(DigestUtils.md5DigestAsHex(hashed.getBytes(StandardCharsets.UTF_8)))
                    .output(output != null && output.length() > 8000 ? output.substring(0, 8000) : output)
                    .latencyMs(latencyMs)
                    .fallbackUsed(fallback)
                    .build());
        } catch (Exception e) {
            log.debug("Não foi possível persistir invocação LLM: {}", e.getMessage());
        }
    }

    private Optional<String> bearer(String companyId) {
        if (authTokenPort != null && StringUtils.hasText(companyId)) {
            try {
                Optional<String> token = authTokenPort.getToken(UUID.fromString(companyId));
                if (token.isPresent()) {
                    return token;
                }
            } catch (IllegalArgumentException ignored) {
                // tenantId legado pode não ser UUID
            }
        }
        if (StringUtils.hasText(llmProperties.getGatewayBearerToken())) {
            return Optional.of(llmProperties.getGatewayBearerToken().trim());
        }
        return Optional.empty();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
