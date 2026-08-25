package com.keepguard.ms_ai_guardian.adapters.in.rest;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.application.dto.ManualDiagnoseRequestDTO;
import com.keepguard.ms_ai_guardian.application.service.AiDiagnosticService;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/guardian")
@RequiredArgsConstructor
@Tag(name = "AI Guardian Diagnostics", description = "Endpoints do Agente de Inteligência Artificial para Diagnóstico de Incidentes")
public class GuardianDiagnosticController {

    private final AiDiagnosticService aiDiagnosticService;
    private final IncidentRepository incidentRepository;

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
    @Operation(summary = "Listar histórico de incidentes e diagnósticos armazenados no banco de dados")
    public ResponseEntity<List<Incident>> listIncidents(@RequestParam(defaultValue = "keepguard") String namespace) {
        return ResponseEntity.ok(incidentRepository.findByNamespaceOrderByCreatedAtDesc(namespace));
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "Obter detalhes de um incidente específico por ID")
    public ResponseEntity<Incident> getIncidentById(@PathVariable UUID id) {
        return incidentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
