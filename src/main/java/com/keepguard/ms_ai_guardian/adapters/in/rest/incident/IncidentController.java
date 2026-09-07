package com.keepguard.ms_ai_guardian.adapters.in.rest.incident;

import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.request.ExecuteActionRequestDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.IncidentActionExecutionResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.IncidentDetailResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.PaginatedIncidentResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.mapper.IncidentAdapterMapper;
import com.keepguard.ms_ai_guardian.application.port.in.IncidentPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/guardian")
@RequiredArgsConstructor
@Tag(name = "AI Guardian Incidents", description = "Consulta e remediação de incidentes")
public class IncidentController {

    private static final List<String> LIST_QUERY_KEYS = List.of(
            "page", "size", "from", "to", "status", "severity", "serviceName", "namespace",
            "k8sConclusion", "errorReason", "correlationId", "q", "sort", "dir"
    );

    private final IncidentPort incidentPort;
    private final IncidentAdapterMapper mapper;

    @GetMapping("/incidents")
    @Operation(summary = "Listar incidentes com paginação e filtros")
    public ResponseEntity<PaginatedIncidentResponseDTO> listIncidents(
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
        return ResponseEntity.ok(mapper.toPage(incidentPort.list(query)));
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "Obter detalhes de um incidente específico por ID")
    public ResponseEntity<IncidentDetailResponseDTO> getIncidentById(@PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDetail(incidentPort.get(id)));
    }

    @PostMapping("/incidents/{id}/actions")
    @Operation(summary = "Executar ação catalogada no incidente")
    public ResponseEntity<IncidentActionExecutionResponseDTO> executeAction(
            @PathVariable UUID id,
            @RequestBody ExecuteActionRequestDTO request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ResponseEntity.ok(mapper.toExecution(incidentPort.execute(
                mapper.toExecuteCommand(id, request, userId, userEmail, userRole, correlationId))));
    }
}
