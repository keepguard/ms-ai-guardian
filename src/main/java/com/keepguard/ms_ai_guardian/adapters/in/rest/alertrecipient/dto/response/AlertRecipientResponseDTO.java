package com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.dto.response;

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
public class AlertRecipientResponseDTO {
    private UUID id;
    private String email;
    private boolean enabled;
    private String label;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
