package com.keepguard.ms_ai_guardian.application.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class ExecuteActionRequest {
    private UUID suggestionId;
    private String confirmation;
}
