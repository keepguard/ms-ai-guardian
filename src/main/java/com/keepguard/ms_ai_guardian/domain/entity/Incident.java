package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.ClosedBy;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.enums.InvestigationSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "incidents", schema = "ms_ai_guardian")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String namespace;

    @Column(nullable = false)
    private String podName;

    @Column(nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String errorReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Column(columnDefinition = "TEXT")
    private String capturedLogsSnippet;

    @Column(columnDefinition = "TEXT")
    private String aiRootCauseAnalysis;

    @Column(columnDefinition = "TEXT")
    private String aiRecommendedAction;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Builder.Default
    @Column(name = "occurrences_count", nullable = false)
    private int occurrencesCount = 1;

    private LocalDateTime lastSeenAt;

    private String targetRecipientEmail;

    private boolean notificationSent;

    private LocalDateTime notificationSentAt;

    @Column(name = "k8s_conclusion", length = 64)
    private String k8sConclusion;

    @Enumerated(EnumType.STRING)
    @Column(name = "investigation_source", length = 32)
    private InvestigationSource investigationSource;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Builder.Default
    @ColumnDefault("0")
    @Column(name = "healthy_streak", nullable = false)
    private int healthyStreak = 0;

    @Column(name = "normalized_at")
    private LocalDateTime normalizedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closed_by", length = 16)
    private ClosedBy closedBy;

    @Column(name = "reopened_from_id")
    private UUID reopenedFromId;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
