package com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response;

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
public class IncidentActionExecutionResponseDTO {
    private UUID id;
    private UUID incidentId;
    private UUID suggestionId;
    private String actorUserId;
    private String actorEmail;
    private String actorRole;
    private String correlationId;
    private String outcome;
    private String beforeJson;
    private String afterJson;
    private String errorMessage;
    private LocalDateTime createdAt;
}
