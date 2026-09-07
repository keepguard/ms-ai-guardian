package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.ClosedBy;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.enums.InvestigationSource;
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
public class Incident {

    private UUID id;

    private String namespace;

    private String podName;

    private String serviceName;

    private String errorReason;

    private IncidentSeverity severity;

    private IncidentStatus status;

    private String capturedLogsSnippet;

    private String aiRootCauseAnalysis;

    private String aiRecommendedAction;

    private String fingerprint;

    @Builder.Default
    private int occurrencesCount = 1;

    private LocalDateTime lastSeenAt;

    private String targetRecipientEmail;

    private boolean notificationSent;

    private LocalDateTime notificationSentAt;

    private String k8sConclusion;

    private InvestigationSource investigationSource;

    private String correlationId;

    @Builder.Default
    private int healthyStreak = 0;

    private LocalDateTime normalizedAt;

    private ClosedBy closedBy;

    private UUID reopenedFromId;

    private String aiSummary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
