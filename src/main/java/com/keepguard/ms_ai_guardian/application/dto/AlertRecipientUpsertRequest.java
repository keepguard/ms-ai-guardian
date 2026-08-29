package com.keepguard.ms_ai_guardian.application.dto;

import lombok.Data;

@Data
public class AlertRecipientUpsertRequest {
    private String email;
    private String label;
    private Boolean enabled;
}
