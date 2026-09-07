package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentJpaMapper {

    public Incident toDomain(IncidentJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return Incident.builder()
                .id(entity.getId())
                .namespace(entity.getNamespace())
                .podName(entity.getPodName())
                .serviceName(entity.getServiceName())
                .errorReason(entity.getErrorReason())
                .severity(entity.getSeverity())
                .status(entity.getStatus())
                .capturedLogsSnippet(entity.getCapturedLogsSnippet())
                .aiRootCauseAnalysis(entity.getAiRootCauseAnalysis())
                .aiRecommendedAction(entity.getAiRecommendedAction())
                .fingerprint(entity.getFingerprint())
                .occurrencesCount(entity.getOccurrencesCount())
                .lastSeenAt(entity.getLastSeenAt())
                .targetRecipientEmail(entity.getTargetRecipientEmail())
                .notificationSent(entity.isNotificationSent())
                .notificationSentAt(entity.getNotificationSentAt())
                .k8sConclusion(entity.getK8sConclusion())
                .investigationSource(entity.getInvestigationSource())
                .correlationId(entity.getCorrelationId())
                .healthyStreak(entity.getHealthyStreak())
                .normalizedAt(entity.getNormalizedAt())
                .closedBy(entity.getClosedBy())
                .reopenedFromId(entity.getReopenedFromId())
                .aiSummary(entity.getAiSummary())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public IncidentJpaEntity toEntity(Incident domain) {
        if (domain == null) {
            return null;
        }
        return IncidentJpaEntity.builder()
                .id(domain.getId())
                .namespace(domain.getNamespace())
                .podName(domain.getPodName())
                .serviceName(domain.getServiceName())
                .errorReason(domain.getErrorReason())
                .severity(domain.getSeverity())
                .status(domain.getStatus())
                .capturedLogsSnippet(domain.getCapturedLogsSnippet())
                .aiRootCauseAnalysis(domain.getAiRootCauseAnalysis())
                .aiRecommendedAction(domain.getAiRecommendedAction())
                .fingerprint(domain.getFingerprint())
                .occurrencesCount(domain.getOccurrencesCount())
                .lastSeenAt(domain.getLastSeenAt())
                .targetRecipientEmail(domain.getTargetRecipientEmail())
                .notificationSent(domain.isNotificationSent())
                .notificationSentAt(domain.getNotificationSentAt())
                .k8sConclusion(domain.getK8sConclusion())
                .investigationSource(domain.getInvestigationSource())
                .correlationId(domain.getCorrelationId())
                .healthyStreak(domain.getHealthyStreak())
                .normalizedAt(domain.getNormalizedAt())
                .closedBy(domain.getClosedBy())
                .reopenedFromId(domain.getReopenedFromId())
                .aiSummary(domain.getAiSummary())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
