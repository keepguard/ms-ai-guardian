package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.LlmInvocationJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class LlmInvocationJpaMapper {

    public LlmInvocation toDomain(LlmInvocationJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return LlmInvocation.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .promptKey(entity.getPromptKey())
                .promptVersion(entity.getPromptVersion())
                .model(entity.getModel())
                .inputHash(entity.getInputHash())
                .output(entity.getOutput())
                .latencyMs(entity.getLatencyMs())
                .fallbackUsed(entity.isFallbackUsed())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public LlmInvocationJpaEntity toEntity(LlmInvocation domain) {
        if (domain == null) {
            return null;
        }
        return LlmInvocationJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .promptKey(domain.getPromptKey())
                .promptVersion(domain.getPromptVersion())
                .model(domain.getModel())
                .inputHash(domain.getInputHash())
                .output(domain.getOutput())
                .latencyMs(domain.getLatencyMs())
                .fallbackUsed(domain.isFallbackUsed())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
