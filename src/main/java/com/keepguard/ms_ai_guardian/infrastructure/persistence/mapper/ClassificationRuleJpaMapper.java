package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.ClassificationRuleEntity;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.ClassificationRuleJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class ClassificationRuleJpaMapper {

    public ClassificationRuleEntity toDomain(ClassificationRuleJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return ClassificationRuleEntity.builder()
                .id(entity.getId())
                .ruleKey(entity.getRuleKey())
                .priority(entity.getPriority())
                .verdict(entity.getVerdict())
                .requiresCodePr(entity.isRequiresCodePr())
                .errorContains(entity.getErrorContains())
                .logsContains(entity.getLogsContains())
                .summaryTemplate(entity.getSummaryTemplate())
                .explanationTemplate(entity.getExplanationTemplate())
                .suggestedActionTemplate(entity.getSuggestedActionTemplate())
                .enabled(entity.isEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public ClassificationRuleJpaEntity toEntity(ClassificationRuleEntity domain) {
        if (domain == null) {
            return null;
        }
        return ClassificationRuleJpaEntity.builder()
                .id(domain.getId())
                .ruleKey(domain.getRuleKey())
                .priority(domain.getPriority())
                .verdict(domain.getVerdict())
                .requiresCodePr(domain.isRequiresCodePr())
                .errorContains(domain.getErrorContains())
                .logsContains(domain.getLogsContains())
                .summaryTemplate(domain.getSummaryTemplate())
                .explanationTemplate(domain.getExplanationTemplate())
                .suggestedActionTemplate(domain.getSuggestedActionTemplate())
                .enabled(domain.isEnabled())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
