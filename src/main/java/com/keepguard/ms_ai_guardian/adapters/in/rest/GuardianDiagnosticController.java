package com.keepguard.ms_ai_guardian.adapters.in.rest;

import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientUpsertRequest;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.application.dto.ExecuteActionRequest;
import com.keepguard.ms_ai_guardian.application.dto.IncidentDetailDTO;
import com.keepguard.ms_ai_guardian.application.dto.ManualDiagnoseRequestDTO;
import com.keepguard.ms_ai_guardian.application.dto.PaginatedIncidentResponse;
import com.keepguard.ms_ai_guardian.application.service.AiDiagnosticService;
import com.keepguard.ms_ai_guardian.application.service.sre.AlertRecipientService;
import com.keepguard.ms_ai_guardian.application.service.sre.IncidentQueryService;
import com.keepguard.ms_ai_guardian.application.service.sre.IncidentRemediationService;
import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/guardian")
@RequiredArgsConstructor
@Tag(name = "AI Guardian Diagnostics", description = "Endpoints do Agente de Inteligência Artificial para Diagnóstico de Incidentes")
public class GuardianDiagnosticController {

    private static final List<String> LIST_QUERY_KEYS = List.of(
            "page", "size", "from", "to", "status", "severity", "serviceName", "namespace",
            "k8sConclusion", "errorReason", "correlationId", "q", "sort", "dir"
    );

    private final AiDiagnosticService aiDiagnosticService;
    private final IncidentQueryService incidentQueryService;
    private final IncidentRemediationService incidentRemediationService;
    private final AlertRecipientService alertRecipientService;
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @PostMapping("/diagnose/async")
    @Operation(summary = "Ingestão assíncrona ultra-rápida (202 Accepted) para suportar tempestades de 1.000+ alertas/minuto")
    public ResponseEntity<com.keepguard.ms_ai_guardian.infrastructure.messaging.dto.IncidentQueueMessage> triggerDiagnosisAsync(@RequestBody ManualDiagnoseRequestDTO request) {
        String namespace = request.getNamespace() != null ? request.getNamespace() : "keepguard";
        String serviceName = request.getServiceName() != null ? request.getServiceName() : request.getPodName();
        String errorReason = request.getErrorReason() != null ? request.getErrorReason() : "MANUAL_ASYNC_TRIGGER";

        var queueMsg = com.keepguard.ms_ai_guardian.infrastructure.messaging.dto.IncidentQueueMessage.builder()
                .trackingId(UUID.randomUUID())
                .namespace(namespace)
                .podName(request.getPodName())
                .serviceName(serviceName)
                .errorReason(errorReason)
                .forceSendEmail(request.isForceSendEmail())
                .enqueuedTimestamp(System.currentTimeMillis())
                .build();

        rabbitTemplate.convertAndSend(
                com.keepguard.ms_ai_guardian.infrastructure.messaging.RabbitMqTopologyConfig.GUARDIAN_INCIDENT_EXCHANGE,
                com.keepguard.ms_ai_guardian.infrastructure.messaging.RabbitMqTopologyConfig.GUARDIAN_INCIDENT_ROUTING_KEY,
                queueMsg
        );

        return ResponseEntity.accepted().body(queueMsg);
    }

    @PostMapping("/diagnose")
    @Operation(summary = "Acionar diagnóstico inteligente sob demanda para um Pod (Síncrono)")
    public ResponseEntity<DiagnosticResultDTO> triggerDiagnosis(@RequestBody ManualDiagnoseRequestDTO request) {
        String namespace = request.getNamespace() != null ? request.getNamespace() : "keepguard";
        String serviceName = request.getServiceName() != null ? request.getServiceName() : request.getPodName();
        String errorReason = request.getErrorReason() != null ? request.getErrorReason() : "MANUAL_TRIGGER";

        DiagnosticResultDTO result = aiDiagnosticService.diagnosePod(
                namespace,
                request.getPodName(),
                serviceName,
                errorReason,
                request.isForceSendEmail()
        );

        return ResponseEntity.ok(result);
    }

    @GetMapping("/incidents")
    @Operation(summary = "Listar incidentes com paginação e filtros")
    public ResponseEntity<PaginatedIncidentResponse> listIncidents(
            @RequestParam(required = false) Map<String, String> params) {
        Map<String, String> query = new LinkedHashMap<>();
        if (params != null) {
            for (String key : LIST_QUERY_KEYS) {
                if (params.containsKey(key) && params.get(key) != null && !params.get(key).isBlank()) {
                    query.put(key, params.get(key));
                }
            }
        }
        query.putIfAbsent("namespace", "keepguard");
        return ResponseEntity.ok(incidentQueryService.list(query));
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "Obter detalhes de um incidente específico por ID")
    public ResponseEntity<IncidentDetailDTO> getIncidentById(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentQueryService.get(id));
    }

    @PostMapping("/incidents/{id}/actions")
    @Operation(summary = "Executar ação catalogada no incidente")
    public ResponseEntity<IncidentActionExecution> executeAction(
            @PathVariable UUID id,
            @RequestBody ExecuteActionRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ResponseEntity.ok(incidentRemediationService.execute(
                id,
                request.getSuggestionId(),
                request.getConfirmation(),
                userId,
                userEmail,
                userRole,
                correlationId));
    }

    @GetMapping("/alert-recipients")
    public ResponseEntity<List<GuardianAlertRecipient>> listRecipients() {
        return ResponseEntity.ok(alertRecipientService.listAll());
    }

    @PutMapping("/alert-recipients")
    public ResponseEntity<GuardianAlertRecipient> upsertRecipient(@RequestBody AlertRecipientUpsertRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        return ResponseEntity.ok(alertRecipientService.upsert(request.getEmail(), request.getLabel(), enabled));
    }

    @PatchMapping("/alert-recipients/{id}")
    public ResponseEntity<GuardianAlertRecipient> patchRecipient(
            @PathVariable UUID id,
            @RequestBody AlertRecipientUpsertRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        return ResponseEntity.ok(alertRecipientService.setEnabled(id, enabled));
    }
}
