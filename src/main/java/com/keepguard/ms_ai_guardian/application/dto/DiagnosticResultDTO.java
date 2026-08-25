package com.keepguard.ms_ai_guardian.application.dto;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosticResultDTO {
    private UUID incidentId;
    private String podName;
    private String namespace;
    private String serviceName;
    private IncidentSeverity severity;
    private String errorReason;
    private String rootCause;
    private String recommendedAction;
    private List<String> technicalDetails;
    private boolean notificationSent;
}
