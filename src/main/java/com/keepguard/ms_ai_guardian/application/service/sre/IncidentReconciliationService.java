package com.keepguard.ms_ai_guardian.application.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.dto.ClusterStormAssessment;
import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.domain.GuardianClusterConstants;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;
import com.keepguard.ms_ai_guardian.domain.enums.ClosedBy;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.enums.LifecycleEventType;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentEvidenceRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentReconciliationService {

    private static final List<IncidentStatus> OPEN = List.of(
            IncidentStatus.DETECTED,
            IncidentStatus.DIAGNOSING,
            IncidentStatus.DIAGNOSED,
            IncidentStatus.NOTIFIED,
            IncidentStatus.AWAITING_HUMAN,
            IncidentStatus.ACTION_RUNNING
    );

    private final IncidentRepository incidentRepository;
    private final KubernetesInspectorService k8sInspector;
    private final IncidentLifecycleService lifecycleService;
    private final AlertFanoutService alertFanoutService;
    private final GuardianAuditPublisher auditPublisher;
    private final IncidentEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;
    private final ClusterStormService clusterStormService;
    private final GuardianProperties guardianProperties;

    @Value("${app.guardian.healthy-streak-required:3}")
    private int streakRequired;

    public void reconcileOpenIncidents() {
        List<Incident> open = incidentRepository.findByStatusIn(OPEN);
        for (Incident incident : open) {
            try {
                reconcileOne(incident);
            } catch (Exception e) {
                log.warn("Falha ao reconciliar incidente {}: {}", incident.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Incident incident) {
        boolean healthy;
        ClusterStormAssessment assessment = null;
        if (GuardianClusterConstants.isClusterIncident(incident.getServiceName())) {
            assessment = k8sInspector.assessClusterStorm(incident.getNamespace(), guardianProperties.getStorm());
            healthy = !assessment.stormActive();
        } else {
            ClusterFacts facts = k8sInspector.collectFacts(
                    incident.getNamespace(), incident.getPodName(), incident.getServiceName());
            healthy = facts.isHealthy();
        }

        if (healthy) {
            incident.setHealthyStreak(incident.getHealthyStreak() + 1);
            lifecycleService.record(incident, LifecycleEventType.HEALTH_CHECK_PASS,
                    "streak=" + incident.getHealthyStreak() + "/" + streakRequired);
            if (incident.getHealthyStreak() >= streakRequired) {
                incident.setStatus(IncidentStatus.NORMALIZED);
                incident.setNormalizedAt(LocalDateTime.now());
                incident.setClosedBy(ClosedBy.WATCHER);
                incident.setLastSeenAt(LocalDateTime.now());
                incidentRepository.save(incident);
                try {
                    if (GuardianClusterConstants.isClusterIncident(incident.getServiceName())) {
                        if (assessment == null) {
                            assessment = k8sInspector.assessClusterStorm(
                                    incident.getNamespace(), guardianProperties.getStorm());
                        }
                        evidenceRepository.save(IncidentEvidence.builder()
                                .incidentId(incident.getId())
                                .kind("STORM_RECOVERED")
                                .payloadJson(objectMapper.writeValueAsString(assessment))
                                .build());
                    } else {
                        ClusterFacts facts = k8sInspector.collectFacts(
                                incident.getNamespace(), incident.getPodName(), incident.getServiceName());
                        evidenceRepository.save(IncidentEvidence.builder()
                                .incidentId(incident.getId())
                                .kind("NORMALIZED_FACTS")
                                .payloadJson(objectMapper.writeValueAsString(facts.toMap()))
                                .build());
                    }
                } catch (Exception ignored) {
                    // evidência é complementar
                }
                lifecycleService.record(incident, LifecycleEventType.NORMALIZED,
                        "Serviço saudável após " + incident.getHealthyStreak() + " varreduras");
                auditPublisher.publish("GUARDIAN_INCIDENT_NORMALIZED", "SUCCESS",
                        incident.getCorrelationId(), "INCIDENT", incident.getId().toString());
                if (GuardianClusterConstants.isClusterIncident(incident.getServiceName())) {
                    if (assessment == null) {
                        assessment = k8sInspector.assessClusterStorm(
                                incident.getNamespace(), guardianProperties.getStorm());
                    }
                    alertFanoutService.fanoutStormNormalized(incident, assessment);
                    clusterStormService.clearStormState(incident.getNamespace());
                } else if (!clusterStormService.isStormActive(incident.getNamespace())) {
                    alertFanoutService.fanoutNormalized(incident);
                }
                log.info("Incidente {} NORMALIZED para serviço {}", incident.getId(), incident.getServiceName());
                return;
            }
            incidentRepository.save(incident);
            return;
        }
        if (incident.getHealthyStreak() > 0 || incident.getStatus() == IncidentStatus.ACTION_RUNNING) {
            lifecycleService.record(incident, LifecycleEventType.HEALTH_CHECK_FAIL,
                    GuardianClusterConstants.isClusterIncident(incident.getServiceName())
                            ? "storm-active"
                            : "unhealthy");
        }
        incident.setHealthyStreak(0);
        incident.setLastSeenAt(LocalDateTime.now());
        incidentRepository.save(incident);
    }
}
