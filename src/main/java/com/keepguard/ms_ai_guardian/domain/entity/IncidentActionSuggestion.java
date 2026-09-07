package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.ActionRisk;
import com.keepguard.ms_ai_guardian.domain.enums.RemediationActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentActionSuggestion {

    private UUID id;

    private UUID incidentId;

    private RemediationActionType actionType;

    private String label;

    private ActionRisk risk;

    private boolean enabled;

    private String disabledReason;

    private String aiRationale;

    private String payloadJson;

    private LocalDateTime createdAt;
}
