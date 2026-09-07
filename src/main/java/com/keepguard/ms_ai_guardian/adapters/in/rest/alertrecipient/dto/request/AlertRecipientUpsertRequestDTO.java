package com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.dto.request;

import lombok.Data;

@Data
public class AlertRecipientUpsertRequestDTO {
    private String email;
    private String label;
    private Boolean enabled;
}
