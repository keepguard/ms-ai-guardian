package com.keepguard.ms_ai_guardian.application.mapper;

import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentActionExecutionViewDTO;
import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;
import org.springframework.stereotype.Component;

@Component
public class GuardianApplicationMapper {

    public AlertRecipientViewDTO toAlertRecipientView(GuardianAlertRecipient entity) {
        if (entity == null) {
            return null;
        }
        return AlertRecipientViewDTO.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .enabled(entity.isEnabled())
                .label(entity.getLabel())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public IncidentActionExecutionViewDTO toExecutionView(IncidentActionExecution entity) {
        if (entity == null) {
            return null;
        }
        return IncidentActionExecutionViewDTO.builder()
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
}
