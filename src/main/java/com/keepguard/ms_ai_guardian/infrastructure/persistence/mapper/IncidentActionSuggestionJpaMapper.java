package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentActionSuggestionJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentActionSuggestionJpaMapper {

    public IncidentActionSuggestion toDomain(IncidentActionSuggestionJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return IncidentActionSuggestion.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .actionType(entity.getActionType())
                .label(entity.getLabel())
                .risk(entity.getRisk())
                .enabled(entity.isEnabled())
                .disabledReason(entity.getDisabledReason())
                .aiRationale(entity.getAiRationale())
                .payloadJson(entity.getPayloadJson())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public IncidentActionSuggestionJpaEntity toEntity(IncidentActionSuggestion domain) {
        if (domain == null) {
            return null;
        }
        return IncidentActionSuggestionJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .actionType(domain.getActionType())
                .label(domain.getLabel())
                .risk(domain.getRisk())
                .enabled(domain.isEnabled())
                .disabledReason(domain.getDisabledReason())
                .aiRationale(domain.getAiRationale())
                .payloadJson(domain.getPayloadJson())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
