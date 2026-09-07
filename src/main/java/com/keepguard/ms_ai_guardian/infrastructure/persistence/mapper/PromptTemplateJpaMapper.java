package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.PromptTemplate;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.PromptTemplateJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateJpaMapper {

    public PromptTemplate toDomain(PromptTemplateJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return PromptTemplate.builder()
                .id(entity.getId())
                .promptKey(entity.getPromptKey())
                .version(entity.getVersion())
                .body(entity.getBody())
                .status(entity.getStatus())
                .checksum(entity.getChecksum())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PromptTemplateJpaEntity toEntity(PromptTemplate domain) {
        if (domain == null) {
            return null;
        }
        return PromptTemplateJpaEntity.builder()
                .id(domain.getId())
                .promptKey(domain.getPromptKey())
                .version(domain.getVersion())
                .body(domain.getBody())
                .status(domain.getStatus())
                .checksum(domain.getChecksum())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
