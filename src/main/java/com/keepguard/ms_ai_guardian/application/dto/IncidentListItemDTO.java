package com.keepguard.ms_ai_guardian.application.dto;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class IncidentListItemDTO {
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
