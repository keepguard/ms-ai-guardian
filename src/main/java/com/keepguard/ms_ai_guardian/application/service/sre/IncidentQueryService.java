package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.IncidentDetailDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentListItemDTO;
import com.keepguard.ms_ai_guardian.application.dto.PaginatedIncidentResponse;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentActionExecutionRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentActionSuggestionRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentAlertDeliveryRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentEvidenceRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentLifecycleEventRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentQueryService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "lastSeenAt", "severity", "status", "serviceName");

    private final IncidentRepository incidentRepository;
    private final IncidentEvidenceRepository evidenceRepository;
    private final IncidentActionSuggestionRepository suggestionRepository;
    private final IncidentActionExecutionRepository executionRepository;
    private final IncidentAlertDeliveryRepository deliveryRepository;
    private final IncidentLifecycleEventRepository lifecycleEventRepository;

    public PaginatedIncidentResponse list(Map<String, String> query) {
        int page = parseInt(query.get("page"), 0);
        int size = Math.min(100, Math.max(1, parseInt(query.get("size"), 20)));
        String sort = query.getOrDefault("sort", "createdAt");
        if (!SORTABLE.contains(sort)) {
            sort = "createdAt";
        }
        Sort.Direction dir = "asc".equalsIgnoreCase(query.get("dir")) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<Incident> result = incidentRepository.findAll(specification(query), PageRequest.of(page, size, Sort.by(dir, sort)));
        List<IncidentListItemDTO> content = result.getContent().stream().map(this::toListItem).toList();
        return PaginatedIncidentResponse.builder()
                .content(content)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    public IncidentDetailDTO get(UUID id) {
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incidente não encontrado"));
        return IncidentDetailDTO.builder()
                .incident(toListItem(incident))
                .aiRootCause(incident.getAiRootCauseAnalysis())
                .aiSummary(incident.getAiSummary())
                .aiRecommendedAction(incident.getAiRecommendedAction())
                .investigationSource(incident.getInvestigationSource() != null ? incident.getInvestigationSource().name() : null)
                .correlationId(incident.getCorrelationId())
                .healthyStreak(incident.getHealthyStreak())
                .capturedLogsSnippet(incident.getCapturedLogsSnippet())
                .evidence(evidenceRepository.findByIncidentIdOrderByCreatedAtDesc(id).stream()
                        .map(e -> IncidentDetailDTO.EvidenceDTO.builder()
                                .id(e.getId()).kind(e.getKind()).payloadJson(e.getPayloadJson()).createdAt(e.getCreatedAt())
                                .build())
                        .toList())
                .suggestions(suggestionRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream()
                        .map(s -> IncidentDetailDTO.SuggestionDTO.builder()
                                .id(s.getId()).actionType(s.getActionType().name()).label(s.getLabel())
                                .risk(s.getRisk().name()).enabled(s.isEnabled())
                                .disabledReason(s.getDisabledReason()).aiRationale(s.getAiRationale())
                                .payloadJson(s.getPayloadJson())
                                .build())
                        .toList())
                .executions(executionRepository.findByIncidentIdOrderByCreatedAtDesc(id).stream()
                        .map(x -> IncidentDetailDTO.ExecutionDTO.builder()
                                .id(x.getId()).suggestionId(x.getSuggestionId()).actorUserId(x.getActorUserId())
                                .outcome(x.getOutcome()).errorMessage(x.getErrorMessage()).createdAt(x.getCreatedAt())
                                .build())
                        .toList())
                .deliveries(deliveryRepository.findByIncidentIdOrderBySentAtDesc(id).stream()
                        .map(d -> IncidentDetailDTO.DeliveryDTO.builder()
                                .email(d.getEmail()).outcome(d.getOutcome().name()).kind(d.getKind()).sentAt(d.getSentAt())
                                .build())
                        .toList())
                .timeline(lifecycleEventRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream()
                        .map(t -> IncidentDetailDTO.TimelineDTO.builder()
                                .eventType(t.getEventType().name()).detail(t.getDetail()).createdAt(t.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }

    private Specification<Incident> specification(Map<String, String> query) {
        return (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            String namespace = query.get("namespace");
            if (namespace != null && !namespace.isBlank()) {
                predicates.add(cb.equal(root.get("namespace"), namespace));
            }
            addEnum(predicates, cb, root.get("status"), query.get("status"), IncidentStatus.class);
            addEnum(predicates, cb, root.get("severity"), query.get("severity"), IncidentSeverity.class);
            eq(predicates, cb, root.get("serviceName"), query.get("serviceName"));
            eq(predicates, cb, root.get("k8sConclusion"), query.get("k8sConclusion"));
            eq(predicates, cb, root.get("errorReason"), query.get("errorReason"));
            eq(predicates, cb, root.get("correlationId"), query.get("correlationId"));
            LocalDateTime from = parseTime(query.get("from"));
            LocalDateTime to = parseTime(query.get("to"));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            String search = query.get("q");
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("serviceName")), like),
                        cb.like(cb.lower(root.get("podName")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("aiSummary"), "")), like)
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private IncidentListItemDTO toListItem(Incident incident) {
        return IncidentListItemDTO.builder()
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

    private static void eq(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Path<String> path, String value) {
        if (value != null && !value.isBlank()) {
            predicates.add(cb.equal(path, value));
        }
    }

    private static <E extends Enum<E>> void addEnum(List<Predicate> predicates,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Path<E> path, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            predicates.add(cb.equal(path, Enum.valueOf(type, value)));
        } catch (IllegalArgumentException ignored) {
            // filtro inválido ignorado
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toLocalDateTime();
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
