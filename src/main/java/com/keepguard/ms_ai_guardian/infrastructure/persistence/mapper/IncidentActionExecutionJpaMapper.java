package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentActionExecutionJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentActionExecutionJpaMapper {

    public IncidentActionExecution toDomain(IncidentActionExecutionJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return IncidentActionExecution.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .suggestionId(entity.getSuggestionId())
                .actorUserId(entity.getActorUserId())
                .actorEmail(entity.getActorEmail())
                .actorRole(entity.getActorRole())
                .correlationId(entity.getCorrelationId())
                .outcome(entity.getOutcome())
                .beforeJson(entity.getBeforeJson())
                .afterJson(entity.getAfterJson())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public IncidentActionExecutionJpaEntity toEntity(IncidentActionExecution domain) {
        if (domain == null) {
            return null;
        }
        return IncidentActionExecutionJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .suggestionId(domain.getSuggestionId())
                .actorUserId(domain.getActorUserId())
                .actorEmail(domain.getActorEmail())
                .actorRole(domain.getActorRole())
                .correlationId(domain.getCorrelationId())
                .outcome(domain.getOutcome())
                .beforeJson(domain.getBeforeJson())
                .afterJson(domain.getAfterJson())
                .errorMessage(domain.getErrorMessage())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
