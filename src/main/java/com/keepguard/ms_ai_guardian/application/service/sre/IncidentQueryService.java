package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.IncidentDetailViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentListItemViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.PaginatedIncidentViewDTO;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentActionExecutionRepositoryPort;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentActionSuggestionRepositoryPort;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentAlertDeliveryRepositoryPort;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentEvidenceRepositoryPort;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentLifecycleEventRepositoryPort;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IncidentQueryService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "lastSeenAt", "severity", "status", "serviceName");

    private final IncidentRepositoryPort incidentRepository;
    private final IncidentEvidenceRepositoryPort evidenceRepository;
    private final IncidentActionSuggestionRepositoryPort suggestionRepository;
    private final IncidentActionExecutionRepositoryPort executionRepository;
    private final IncidentAlertDeliveryRepositoryPort deliveryRepository;
    private final IncidentLifecycleEventRepositoryPort lifecycleEventRepository;

    public PaginatedIncidentViewDTO list(Map<String, String> query) {
        int page = parseInt(query.get("page"), 0);
        int size = Math.min(100, Math.max(1, parseInt(query.get("size"), 20)));
        String sort = query.getOrDefault("sort", "createdAt");
        if (!SORTABLE.contains(sort)) {
            sort = "createdAt";
        }
        Sort.Direction dir = "asc".equalsIgnoreCase(query.get("dir")) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<Incident> result = incidentRepository.search(query, PageRequest.of(page, size, Sort.by(dir, sort)));
        List<IncidentListItemViewDTO> content = result.getContent().stream().map(this::toListItem).toList();
        return PaginatedIncidentViewDTO.builder()
                .content(content)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    public IncidentDetailViewDTO get(UUID id) {
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incidente não encontrado"));
        return IncidentDetailViewDTO.builder()
                .incident(toListItem(incident))
                .aiRootCause(incident.getAiRootCauseAnalysis())
                .aiSummary(incident.getAiSummary())
                .aiRecommendedAction(incident.getAiRecommendedAction())
                .investigationSource(incident.getInvestigationSource() != null ? incident.getInvestigationSource().name() : null)
                .correlationId(incident.getCorrelationId())
                .healthyStreak(incident.getHealthyStreak())
                .capturedLogsSnippet(incident.getCapturedLogsSnippet())
                .evidence(evidenceRepository.findByIncidentIdOrderByCreatedAtDesc(id).stream()
                        .map(e -> IncidentDetailViewDTO.EvidenceDTO.builder()
                                .id(e.getId()).kind(e.getKind()).payloadJson(e.getPayloadJson()).createdAt(e.getCreatedAt())
                                .build())
                        .toList())
                .suggestions(suggestionRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream()
                        .map(s -> IncidentDetailViewDTO.SuggestionDTO.builder()
                                .id(s.getId()).actionType(s.getActionType().name()).label(s.getLabel())
                                .risk(s.getRisk().name()).enabled(s.isEnabled())
                                .disabledReason(s.getDisabledReason()).aiRationale(s.getAiRationale())
                                .payloadJson(s.getPayloadJson())
                                .build())
                        .toList())
                .executions(executionRepository.findByIncidentIdOrderByCreatedAtDesc(id).stream()
                        .map(x -> IncidentDetailViewDTO.ExecutionDTO.builder()
                                .id(x.getId()).suggestionId(x.getSuggestionId()).actorUserId(x.getActorUserId())
                                .outcome(x.getOutcome()).errorMessage(x.getErrorMessage()).createdAt(x.getCreatedAt())
                                .build())
                        .toList())
                .deliveries(deliveryRepository.findByIncidentIdOrderBySentAtDesc(id).stream()
                        .map(d -> IncidentDetailViewDTO.DeliveryDTO.builder()
                                .email(d.getEmail()).outcome(d.getOutcome().name()).kind(d.getKind()).sentAt(d.getSentAt())
                                .build())
                        .toList())
                .timeline(lifecycleEventRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream()
                        .map(t -> IncidentDetailViewDTO.TimelineDTO.builder()
                                .eventType(t.getEventType().name()).detail(t.getDetail()).createdAt(t.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }

    private IncidentListItemViewDTO toListItem(Incident incident) {
        return IncidentListItemViewDTO.builder()
                .id(incident.getId())
                .namespace(incident.getNamespace())
                .serviceName(incident.getServiceName())
                .podName(incident.getPodName())
                .status(incident.getStatus())
                .severity(incident.getSeverity())
                .k8sConclusion(incident.getK8sConclusion())
                .errorReason(incident.getErrorReason())
                .occurrencesCount(incident.getOccurrencesCount())
                .emailSent(incident.isNotificationSent())
                .lastSeenAt(incident.getLastSeenAt())
                .createdAt(incident.getCreatedAt())
                .normalizedAt(incident.getNormalizedAt())
                .build();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

