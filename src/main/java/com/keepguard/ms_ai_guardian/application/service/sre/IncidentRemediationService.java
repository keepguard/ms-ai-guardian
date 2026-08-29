package com.keepguard.ms_ai_guardian.application.service.sre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;
import com.keepguard.ms_ai_guardian.domain.enums.ActionRisk;
import com.keepguard.ms_ai_guardian.domain.enums.ClosedBy;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.enums.LifecycleEventType;
import com.keepguard.ms_ai_guardian.domain.enums.RemediationActionType;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentActionExecutionRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentActionSuggestionRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import com.keepguard.ms_ai_guardian.infrastructure.lock.DistributedDeployLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentRemediationService {

    private final IncidentRepository incidentRepository;
    private final IncidentActionSuggestionRepository suggestionRepository;
    private final IncidentActionExecutionRepository executionRepository;
    private final KubernetesInspectorService k8sInspector;
    private final DistributedDeployLockService deployLockService;
    private final IncidentLifecycleService lifecycleService;
    private final AlertFanoutService alertFanoutService;
    private final GuardianAuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    public IncidentActionExecution execute(UUID incidentId, UUID suggestionId, String confirmation,
            String actorUserId, String actorEmail, String actorRole, String correlationId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incidente não encontrado"));
        if (incident.getStatus() == IncidentStatus.NORMALIZED || incident.getStatus() == IncidentStatus.DISMISSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Incidente já encerrado");
        }
        IncidentActionSuggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sugestão não encontrada"));
        if (!suggestion.getIncidentId().equals(incidentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sugestão não pertence ao incidente");
        }
        if (!suggestion.isEnabled() && suggestion.getActionType() != RemediationActionType.DISMISS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Opção não está habilitada: " + suggestion.getDisabledReason());
        }
        if (suggestion.getRisk() == ActionRisk.DESTRUCTIVE) {
            if (confirmation == null || !confirmation.equalsIgnoreCase(incident.getServiceName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Confirme digitando o nome do serviço: " + incident.getServiceName());
            }
        }

        ClusterFacts before = k8sInspector.collectFacts(
                incident.getNamespace(), incident.getPodName(), incident.getServiceName());
        String cid = correlationId != null ? correlationId : incident.getCorrelationId();
        auditPublisher.publish("GUARDIAN_REMEDIATION_REQUESTED", "SUCCESS", cid, "INCIDENT",
                incidentId.toString(), "USER", actorUserId);

        if (suggestion.getActionType() == RemediationActionType.DISMISS) {
            incident.setStatus(IncidentStatus.DISMISSED);
            incident.setClosedBy(ClosedBy.HUMAN);
            incident.setNormalizedAt(LocalDateTime.now());
            incidentRepository.save(incident);
            lifecycleService.record(incident, LifecycleEventType.DISMISSED, actorUserId);
            auditPublisher.publish("GUARDIAN_INCIDENT_DISMISSED", "SUCCESS", cid, "INCIDENT",
                    incidentId.toString(), "USER", actorUserId);
            return saveExecution(incident, suggestion, actorUserId, actorEmail, actorRole, cid, "SUCCESS",
                    before, before, null);
        }

        String lockId = "remediation_" + incidentId + "_" + System.currentTimeMillis();
        if (!deployLockService.tryAcquireDeployLock(incident.getServiceName(), lockId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um rollout em andamento para este serviço");
        }
        try {
            apply(incident, suggestion, before);
            ClusterFacts after = k8sInspector.collectFacts(
                    incident.getNamespace(), incident.getPodName(), incident.getServiceName());
            incident.setStatus(IncidentStatus.ACTION_RUNNING);
            incident.setHealthyStreak(0);
            incidentRepository.save(incident);
            lifecycleService.record(incident, LifecycleEventType.ACTION_APPLIED, suggestion.getActionType().name());
            auditPublisher.publish("GUARDIAN_REMEDIATION_APPLIED", "SUCCESS", cid, "INCIDENT",
                    incidentId.toString(), "USER", actorUserId);
            var execution = saveExecution(incident, suggestion, actorUserId, actorEmail, actorRole, cid, "SUCCESS",
                    before, after, null);
            alertFanoutService.fanoutAction(incident, suggestion.getLabel(), "SUCCESS");
            return execution;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            auditPublisher.publish("GUARDIAN_REMEDIATION_FAILED", "FAILURE", cid, "INCIDENT",
                    incidentId.toString(), "USER", actorUserId);
            lifecycleService.record(incident, LifecycleEventType.ACTION_FAILED, e.getMessage());
            saveExecution(incident, suggestion, actorUserId, actorEmail, actorRole, cid, "FAILURE",
                    before, before, e.getMessage());
            alertFanoutService.fanoutAction(incident, suggestion.getLabel(), "FAILURE");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao aplicar ação no cluster: " + e.getMessage());
        } finally {
            deployLockService.releaseDeployLock(incident.getServiceName());
        }
    }

    private void apply(Incident incident, IncidentActionSuggestion suggestion, ClusterFacts facts) throws Exception {
        JsonNode payload = suggestion.getPayloadJson() != null
                ? objectMapper.readTree(suggestion.getPayloadJson())
                : objectMapper.createObjectNode();
        String namespace = incident.getNamespace();
        String deployment = text(payload, "deploymentName", facts.getDeploymentName());
        String podName = text(payload, "podName", facts.getPodName());
        int desired = payload.has("desiredReplicas") ? payload.get("desiredReplicas").asInt(1) : 1;
        switch (suggestion.getActionType()) {
            case RECREATE_POD -> {
                if (podName == null || podName.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pod alvo não encontrado para recriar");
                }
                k8sInspector.deletePod(namespace, podName);
            }
            case ROLLOUT_RESTART -> k8sInspector.rolloutRestart(namespace, deployment);
            case ROLLBACK_REVISION -> k8sInspector.rollbackRevision(namespace, deployment);
            case SCALE_REPLAY -> {
                k8sInspector.scaleDeployment(namespace, deployment, 0);
                k8sInspector.scaleDeployment(namespace, deployment, Math.max(desired, 1));
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ação não suportada");
        }
    }

    private IncidentActionExecution saveExecution(Incident incident, IncidentActionSuggestion suggestion,
            String actorUserId, String actorEmail, String actorRole, String cid, String outcome,
            ClusterFacts before, ClusterFacts after, String error) {
        try {
            return executionRepository.save(IncidentActionExecution.builder()
                    .incidentId(incident.getId())
                    .suggestionId(suggestion.getId())
                    .actorUserId(actorUserId)
                    .actorEmail(actorEmail)
                    .actorRole(actorRole)
                    .correlationId(cid)
                    .outcome(outcome)
                    .beforeJson(objectMapper.writeValueAsString(before.toMap()))
                    .afterJson(objectMapper.writeValueAsString(after.toMap()))
                    .errorMessage(error)
                    .build());
        } catch (Exception e) {
            return executionRepository.save(IncidentActionExecution.builder()
                    .incidentId(incident.getId())
                    .suggestionId(suggestion.getId())
                    .actorUserId(actorUserId)
                    .outcome(outcome)
                    .errorMessage(error)
                    .build());
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node != null && node.has(field) && !node.get(field).asText("").isBlank()) {
            return node.get(field).asText();
        }
        return fallback;
    }
}
