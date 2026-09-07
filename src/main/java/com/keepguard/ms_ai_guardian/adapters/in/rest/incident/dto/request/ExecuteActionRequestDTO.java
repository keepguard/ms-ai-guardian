package com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.request;

import lombok.Data;

import java.util.UUID;

@Data
public class ExecuteActionRequestDTO {
    private UUID suggestionId;
    private String confirmation;
}
