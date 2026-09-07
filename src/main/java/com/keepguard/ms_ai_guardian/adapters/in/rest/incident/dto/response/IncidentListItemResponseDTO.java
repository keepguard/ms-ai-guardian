package com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
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
public class IncidentListItemResponseDTO {
    private UUID id;
    private String namespace;
    private String serviceName;
    private String podName;
    private IncidentStatus status;
    private IncidentSeverity severity;
    private String k8sConclusion;
    private String errorReason;
    private int occurrencesCount;
    private boolean emailSent;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime normalizedAt;
}
