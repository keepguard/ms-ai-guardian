package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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
    private String errorReason; // e.g. CrashLoopBackOff, OOMKilled, Error, HighErrorRate

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

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
