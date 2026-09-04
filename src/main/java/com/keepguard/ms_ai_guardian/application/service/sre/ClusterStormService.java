package com.keepguard.ms_ai_guardian.application.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.dto.ClusterStormAssessment;
import com.keepguard.ms_ai_guardian.application.dto.ClusterStormState;
import com.keepguard.ms_ai_guardian.application.port.out.cache.ClusterStormStatePort;
import com.keepguard.ms_ai_guardian.domain.GuardianClusterConstants;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.enums.LifecycleEventType;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentEvidenceRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.i18n.GuardianPortuguese;
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
public class ClusterStormService {

    private final KubernetesInspectorService k8sInspector;
    private final GuardianProperties properties;
    private final ClusterStormStatePort stormStatePort;
    private final IncidentRepository incidentRepository;
    private final IncidentLifecycleService lifecycleService;
    private final AlertFanoutService alertFanoutService;
    private final GuardianAuditPublisher auditPublisher;
    private final IncidentEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    /**
     * @return true se a varredura foi tratada em modo tempestade (pular diagnósticos individuais)
     */
    public boolean handleWatcherScan(String namespace) {
        ClusterStormAssessment assessment = k8sInspector.assessClusterStorm(namespace, properties.getStorm());
        Optional<ClusterStormState> existingState = stormStatePort.get(namespace);

        if (assessment.stormActive()) {
            handleActiveStorm(namespace, assessment, existingState.orElse(null));
            return true;
        }

        if (existingState.isPresent()) {
            log.info("[AI Guardian Storm] Tempestade encerrada no namespace {} ({})",
                    namespace, assessment.stormReason());
            stormStatePort.clear(namespace);
        }
        return false;
    }

    public boolean isStormActive(String namespace) {
        return stormStatePort.get(namespace).isPresent()
                || k8sInspector.assessClusterStorm(namespace, properties.getStorm()).stormActive();
    }

    public void clearStormState(String namespace) {
        stormStatePort.clear(namespace);
    }

    private void handleActiveStorm(String namespace, ClusterStormAssessment assessment, ClusterStormState prior) {
        ClusterStormState state = prior != null ? prior : new ClusterStormState();
        state.setNamespace(namespace);
        state.setNodeNotReady(assessment.nodeNotReady());
        state.setAffectedServices(assessment.unavailableServiceNames());
        state.setConfirmStreak(prior != null ? prior.getConfirmStreak() + 1 : 1);

        if (state.getStartedAtEpochMs() <= 0) {
            state.setStartedAtEpochMs(System.currentTimeMillis());
        }

        boolean immediateAlert = assessment.nodeNotReady();
        int confirmRequired = Math.max(1, properties.getStorm().getInfraAlertConfirmScans());
        boolean confirmed = immediateAlert || state.getConfirmStreak() >= confirmRequired;

        if (!confirmed) {
            log.info("[AI Guardian Storm] Condição de tempestade detectada em {} ({}/{} indisponíveis, streak={}/{}). Aguardando confirmação.",
                    namespace,
                    assessment.unavailableDeployments(),
                    assessment.totalDeployments(),
                    state.getConfirmStreak(),
                    confirmRequired);
            stormStatePort.save(namespace, state, properties.getStorm().getStateTtlSeconds());
            return;
        }

        Incident incident = resolveOpenClusterIncident(namespace, assessment, state);
        state.setIncidentId(incident.getId());
        stormStatePort.save(namespace, state, properties.getStorm().getStateTtlSeconds());

        if (!incident.isNotificationSent()) {
            boolean sent = alertFanoutService.fanoutStormOpened(incident, assessment);
            if (sent) {
                incident.setNotificationSent(true);
                incident.setNotificationSentAt(LocalDateTime.now());
                incident.setStatus(IncidentStatus.AWAITING_HUMAN);
                incidentRepository.save(incident);
                lifecycleService.record(incident, LifecycleEventType.ALERTED, "e-mail tempestade cluster");
            }
        } else {
            incident.setOccurrencesCount(incident.getOccurrencesCount() + 1);
            incident.setLastSeenAt(LocalDateTime.now());
            incident.setHealthyStreak(0);
            incidentRepository.save(incident);
        }

        log.warn("[AI Guardian Storm] Tempestade ativa em {} | motivo={} | afetados={}/{} | serviços={}",
                namespace,
                assessment.stormReason(),
                assessment.unavailableDeployments(),
                assessment.totalDeployments(),
                assessment.unavailableServiceNames().size());
    }

    private Incident resolveOpenClusterIncident(String namespace, ClusterStormAssessment assessment, ClusterStormState state) {
        if (state.getIncidentId() != null) {
            Optional<Incident> byId = incidentRepository.findById(state.getIncidentId());
            if (byId.isPresent() && isOpen(byId.get())) {
                return updateClusterIncident(byId.get(), assessment);
            }
        }

        String fingerprint = GuardianClusterConstants.clusterFingerprint(namespace);
        Optional<Incident> existing = incidentRepository.findFirstByFingerprintOrderByCreatedAtDesc(fingerprint);
        if (existing.isPresent() && isOpen(existing.get())) {
            return updateClusterIncident(existing.get(), assessment);
        }

        Incident incident = Incident.builder()
                .namespace(namespace)
                .serviceName(GuardianClusterConstants.CLUSTER_SERVICE_NAME)
                .podName(GuardianClusterConstants.CLUSTER_POD_NAME)
                .errorReason(GuardianClusterConstants.CLUSTER_ERROR_REASON)
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.DETECTED)
                .fingerprint(fingerprint)
                .occurrencesCount(1)
                .lastSeenAt(LocalDateTime.now())
                .k8sConclusion(assessment.nodeNotReady() ? "NODE_FAILURE" : "TRANSIENT_INFRA_RECOVERABLE")
                .aiSummary(buildStormSummary(assessment))
                .aiRootCauseAnalysis("Tempestade de infraestrutura: " + GuardianPortuguese.stormReason(assessment.stormReason()))
                .aiRecommendedAction("Aguardar recuperação do cluster. Verificar saúde do nó e kubelet.")
                .targetRecipientEmail(properties.getDefaultRecipient())
                .notificationSent(false)
                .correlationId(UUID.randomUUID().toString())
                .healthyStreak(0)
                .build();

        incident = incidentRepository.save(incident);
        lifecycleService.record(incident, LifecycleEventType.DETECTED,
                GuardianPortuguese.stormReason(assessment.stormReason()));
        auditPublisher.publish("GUARDIAN_CLUSTER_STORM_OPENED", "SUCCESS",
                incident.getCorrelationId(), "INCIDENT", incident.getId().toString());
        persistStormEvidence(incident.getId(), assessment);
        return incident;
    }

    private Incident updateClusterIncident(Incident incident, ClusterStormAssessment assessment) {
        incident.setLastSeenAt(LocalDateTime.now());
        incident.setAiSummary(buildStormSummary(assessment));
        incident.setK8sConclusion(assessment.nodeNotReady() ? "NODE_FAILURE" : "TRANSIENT_INFRA_RECOVERABLE");
        persistStormEvidence(incident.getId(), assessment);
        return incidentRepository.save(incident);
    }

    private void persistStormEvidence(UUID incidentId, ClusterStormAssessment assessment) {
        try {
            evidenceRepository.save(IncidentEvidence.builder()
                    .incidentId(incidentId)
                    .kind("STORM_ASSESSMENT")
                    .payloadJson(objectMapper.writeValueAsString(assessment))
                    .build());
        } catch (Exception e) {
            log.debug("Falha ao persistir evidência de tempestade: {}", e.getMessage());
        }
    }

    private static String buildStormSummary(ClusterStormAssessment assessment) {
        List<String> services = assessment.unavailableServiceNames();
        String sample = services.size() > 8
                ? String.join(", ", services.subList(0, 8)) + "… (+" + (services.size() - 8) + ")"
                : String.join(", ", services);
        return assessment.unavailableDeployments() + " de " + assessment.totalDeployments()
                + " deployments indisponíveis (" + assessment.unavailablePercent() + "%). "
                + "Motivo: " + GuardianPortuguese.stormReason(assessment.stormReason())
                + (sample.isBlank() ? "" : ". Ex.: " + sample);
    }

    private static boolean isOpen(Incident incident) {
        return incident.getStatus() != IncidentStatus.NORMALIZED
                && incident.getStatus() != IncidentStatus.DISMISSED;
    }
}
