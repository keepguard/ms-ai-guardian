package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.DeliveryOutcome;
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
public class IncidentAlertDelivery {

    private UUID id;

    private UUID incidentId;

    private String email;

    private DeliveryOutcome outcome;

    private String kind;

    private String correlationId;

    private LocalDateTime sentAt;
}
