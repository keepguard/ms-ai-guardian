package com.keepguard.ms_ai_guardian.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteActionCommandDTO {
    private UUID incidentId;
    private UUID suggestionId;
    private String confirmation;
    private String actorUserId;
    private String actorEmail;
    private String actorRole;
    private String correlationId;
}
