package com.keepguard.ms_ai_guardian.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRecipientUpsertCommandDTO {
    private String email;
    private String label;
    private Boolean enabled;
}
