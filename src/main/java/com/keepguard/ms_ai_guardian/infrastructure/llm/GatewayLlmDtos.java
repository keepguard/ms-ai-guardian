package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

final class GatewayLlmDtos {

    private GatewayLlmDtos() {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record CompleteRequest(
            String providerId,
            String model,
            List<Message> messages,
            Integer maxTokens,
            Double temperature,
            String feature,
            String companyId,
            String correlationId,
            String sourceService
    ) {}

    record Message(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompleteResponse(
            String content,
            String model,
            String providerType,
            Usage usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Double estimatedCostUsd,
            Integer latencyMs
    ) {}
}
