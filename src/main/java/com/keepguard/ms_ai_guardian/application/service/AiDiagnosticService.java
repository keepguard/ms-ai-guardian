package com.keepguard.ms_ai_guardian.application.service;

import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.application.service.agents.BusinessAnalystAgentService;
import com.keepguard.ms_ai_guardian.domain.enums.ClassificationVerdict;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.application.service.sre.AlertFanoutService;
import com.keepguard.ms_ai_guardian.application.service.sre.IncidentInvestigationRecorder;
import com.keepguard.ms_ai_guardian.application.service.sre.IncidentLifecycleService;
import com.keepguard.ms_ai_guardian.application.service.sre.LlmInvestigationService;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.enums.LifecycleEventType;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentActionSuggestionRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiDiagnosticService {

    private final KubernetesInspectorService k8sInspector;
    private final IncidentRepository incidentRepository;
    private final BusinessAnalystAgentService businessAnalystAgentService;
    private final Optional<com.keepguard.ms_ai_guardian.application.service.agents.CoderAgentService> coderAgentService;
    private final Optional<com.keepguard.ms_ai_guardian.application.service.agents.ReviewerAgentService> reviewerAgentService;
    private final LlmInvestigationService llmInvestigationService;
    private final IncidentInvestigationRecorder investigationRecorder;
    private final IncidentLifecycleService lifecycleService;
    private final AlertFanoutService alertFanoutService;
    private final IncidentActionSuggestionRepository suggestionRepository;
    private final GuardianAuditPublisher auditPublisher;
    private final GuardianProperties guardianProperties;

    public DiagnosticResultDTO diagnosePod(String namespace, String podName, String serviceName, String errorReason, boolean forceSendEmail) {
        log.info("Iniciando diagnóstico inteligente para pod: {}/{} | Serviço: {}", namespace, podName, serviceName);

        ClusterFacts facts = k8sInspector.collectFacts(namespace, podName, serviceName);
        String recentLogs = facts.getLogsSnippet() != null ? facts.getLogsSnippet() : "";
        List<String> warningEvents = facts.getWarningEvents();
        IncidentSeverity severity = evaluateSeverity(errorReason, recentLogs, facts.getDescribe() != null ? facts.getDescribe() : "");
        String fingerprint = generateIncidentFingerprint(serviceName, errorReason, recentLogs);

        Optional<Incident> existingIncidentOpt = incidentRepository.findFirstByFingerprintOrderByCreatedAtDesc(fingerprint);
        Incident incident;
        boolean isNew = false;
        UUID reopenedFrom = null;

        if (existingIncidentOpt.isPresent()) {
            Incident existing = existingIncidentOpt.get();
            if (existing.getStatus() == IncidentStatus.NORMALIZED || existing.getStatus() == IncidentStatus.DISMISSED) {
                reopenedFrom = existing.getId();
                isNew = true;
                incident = newOpenIncident(namespace, podName, serviceName, errorReason, severity, fingerprint, recentLogs);
                incident.setReopenedFromId(reopenedFrom);
            } else {
                existing.setOccurrencesCount(existing.getOccurrencesCount() + 1);
                existing.setLastSeenAt(LocalDateTime.now());
                existing.setHealthyStreak(0);
                existing.setPodName(facts.getPodName() != null ? facts.getPodName() : existing.getPodName());
                incident = incidentRepository.save(existing);
                if (!forceSendEmail && existing.isNotificationSent()) {
                    log.info("Incidente {} atualizado (ocorrência #{}), cooldown de e-mail ativo.",
                            existing.getId(), existing.getOccurrencesCount());
                    return toDto(existing, warningEvents, false);
                }
            }
        } else {
            isNew = true;
            incident = newOpenIncident(namespace, podName, serviceName, errorReason, severity, fingerprint, recentLogs);
        }

        if (isNew) {
            incident = incidentRepository.save(incident);
            lifecycleService.record(incident, LifecycleEventType.DETECTED,
                    reopenedFrom != null ? "Reaberto a partir de " + reopenedFrom : errorReason);
            if (reopenedFrom != null) {
                lifecycleService.record(incident, LifecycleEventType.REOPENED, reopenedFrom.toString());
            }
            auditPublisher.publish("GUARDIAN_INCIDENT_OPENED", "SUCCESS", incident.getCorrelationId(),
                    "INCIDENT", incident.getId().toString());
        }

        LlmInvestigationResult llm = llmInvestigationService.investigate(facts, errorReason);
        investigationRecorder.persistInvestigation(incident, facts, llm);
        incident = incidentRepository.save(incident);
        lifecycleService.record(incident, LifecycleEventType.INVESTIGATED,
                (incident.getInvestigationSource() != null ? incident.getInvestigationSource().name() : "HEURISTIC")
                        + " " + incident.getK8sConclusion());

        DiagnosticResultDTO resultDTO = toDto(incident, warningEvents, false);
        var businessVerdict = businessAnalystAgentService.evaluateIncident(resultDTO, recentLogs);

        var suggestions = suggestionRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getId());
        if (shouldDeferInfraAlert(incident, facts, errorReason, forceSendEmail)) {
            log.info("Incidente {} — alerta de infra deferido ({}/{} varreduras).",
                    incident.getId(),
                    incident.getOccurrencesCount(),
                    guardianProperties.getStorm().getInfraAlertConfirmScans());
            incidentRepository.save(incident);
            return resultDTO;
        }
        boolean mesaSent = alertFanoutService.fanoutOpened(incident, suggestions);
        if (mesaSent) {
            lifecycleService.record(incident, LifecycleEventType.ALERTED, "e-mail mesa SRE");
            incident.setStatus(IncidentStatus.AWAITING_HUMAN);
            incident.setNotificationSent(true);
            incident.setNotificationSentAt(LocalDateTime.now());
            incidentRepository.save(incident);
            resultDTO.setNotificationSent(true);
        }

        if (businessVerdict.type() == ClassificationVerdict.INFRASTRUCTURE_FAULT || !businessVerdict.requiresCodePr()) {
            log.info("BusinessAnalystAgent classificou {}. PR de código não será aberto.", businessVerdict.type());
            return resultDTO;
        }

        if (coderAgentService.isPresent() && reviewerAgentService.isPresent()
                && !serviceName.contains("deployment") && !serviceName.contains("busybox")) {
            try {
                var prOpt = coderAgentService.get().createHotfixPullRequest(resultDTO, recentLogs, businessVerdict);
                prOpt.ifPresent(reviewerAgentService.get()::performReview);
            } catch (Exception e) {
                log.error("Falha ao executar pipeline Multi-Agent de hotfix: {}", e.getMessage());
            }
        }

        return resultDTO;
    }

    private Incident newOpenIncident(String namespace, String podName, String serviceName, String errorReason,
            IncidentSeverity severity, String fingerprint, String recentLogs) {
        return Incident.builder()
                .podName(podName)
                .namespace(namespace)
                .serviceName(serviceName)
                .severity(severity)
                .errorReason(errorReason)
                .fingerprint(fingerprint)
                .occurrencesCount(1)
                .lastSeenAt(LocalDateTime.now())
                .capturedLogsSnippet(recentLogs.length() > 3000 ? recentLogs.substring(recentLogs.length() - 3000) : recentLogs)
                .status(IncidentStatus.DETECTED)
                .targetRecipientEmail(guardianProperties.getDefaultRecipient())
                .notificationSent(false)
                .correlationId(UUID.randomUUID().toString())
                .healthyStreak(0)
                .build();
    }

    private DiagnosticResultDTO toDto(Incident incident, List<String> warningEvents, boolean notificationSent) {
        return DiagnosticResultDTO.builder()
                .incidentId(incident.getId())
                .podName(incident.getPodName())
                .namespace(incident.getNamespace())
                .serviceName(incident.getServiceName())
                .severity(incident.getSeverity())
                .errorReason(incident.getErrorReason())
                .rootCause(incident.getAiRootCauseAnalysis())
                .recommendedAction(incident.getAiRecommendedAction())
                .technicalDetails(warningEvents)
                .notificationSent(notificationSent)
                .build();
    }

    private String generateIncidentFingerprint(String serviceName, String errorReason, String recentLogs) {
        String normalizedError = errorReason != null ? errorReason.trim().toLowerCase() : "unknown";
        String normalizedService = serviceName != null ? serviceName.trim().toLowerCase() : "unknown";
        String location = com.keepguard.ms_ai_guardian.infrastructure.util.IncidentSourceLocator
                .fingerprintLocation(recentLogs, errorReason)
                .orElse("general");
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                (normalizedService + ":" + normalizedError + ":" + location).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private IncidentSeverity evaluateSeverity(String errorReason, String logs, String podHealth) {
        String health = podHealth != null ? podHealth : "";
        String logText = logs != null ? logs : "";
        if (health.contains("OOMKilled") || logText.contains("OutOfMemoryError") || "CrashLoopBackOff".equalsIgnoreCase(errorReason)) {
            return IncidentSeverity.CRITICAL;
        }
        if (logText.contains("Connection refused") || logText.contains("HikariPool") || logText.contains("PSQLException")) {
            return IncidentSeverity.HIGH;
        }
        if (logText.contains("NullPointerException") || logText.contains("FeignException")) {
            return IncidentSeverity.HIGH;
        }
        return IncidentSeverity.MEDIUM;
    }

    private boolean shouldDeferInfraAlert(Incident incident, ClusterFacts facts, String errorReason,
            boolean forceSendEmail) {
        if (forceSendEmail || incident.isNotificationSent()) {
            return false;
        }
        if (!"SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE".equals(errorReason)) {
            return false;
        }
        K8sConclusion conclusion = facts.getConclusion();
        if (conclusion != K8sConclusion.NODE_FAILURE
                && conclusion != K8sConclusion.TRANSIENT_INFRA_RECOVERABLE) {
            return false;
        }
        return incident.getOccurrencesCount() < guardianProperties.getStorm().getInfraAlertConfirmScans();
    }
}
