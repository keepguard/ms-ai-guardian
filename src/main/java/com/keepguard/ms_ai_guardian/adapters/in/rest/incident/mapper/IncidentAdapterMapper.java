package com.keepguard.ms_ai_guardian.adapters.in.rest.incident.mapper;

import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.request.ExecuteActionRequestDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.IncidentActionExecutionResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.IncidentDetailResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.IncidentListItemResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response.PaginatedIncidentResponseDTO;
import com.keepguard.ms_ai_guardian.application.dto.ExecuteActionCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentActionExecutionViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentDetailViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentListItemViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.PaginatedIncidentViewDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class IncidentAdapterMapper {

    public ExecuteActionCommandDTO toExecuteCommand(
            UUID incidentId,
            ExecuteActionRequestDTO request,
            String userId,
            String userEmail,
            String userRole,
            String correlationId) {
        ExecuteActionRequestDTO body = request != null ? request : new ExecuteActionRequestDTO();
        return ExecuteActionCommandDTO.builder()
                .incidentId(incidentId)
                .suggestionId(body.getSuggestionId())
                .confirmation(body.getConfirmation())
                .actorUserId(userId)
                .actorEmail(userEmail)
                .actorRole(userRole)
                .correlationId(correlationId)
                .build();
    }

    public IncidentListItemResponseDTO toListItem(IncidentListItemViewDTO view) {
        if (view == null) {
            return null;
        }
        return IncidentListItemResponseDTO.builder()
                .id(view.getId())
                .namespace(view.getNamespace())
                .serviceName(view.getServiceName())
                .podName(view.getPodName())
                .status(view.getStatus())
                .severity(view.getSeverity())
                .k8sConclusion(view.getK8sConclusion())
                .errorReason(view.getErrorReason())
                .occurrencesCount(view.getOccurrencesCount())
                .emailSent(view.isEmailSent())
                .lastSeenAt(view.getLastSeenAt())
                .createdAt(view.getCreatedAt())
                .normalizedAt(view.getNormalizedAt())
                .build();
    }

    public PaginatedIncidentResponseDTO toPage(PaginatedIncidentViewDTO view) {
        if (view == null) {
            return null;
        }
        List<IncidentListItemResponseDTO> content = view.getContent() == null
                ? List.of()
                : view.getContent().stream().map(this::toListItem).toList();
        return PaginatedIncidentResponseDTO.builder()
                .content(content)
                .page(view.getPage())
                .size(view.getSize())
                .totalElements(view.getTotalElements())
                .totalPages(view.getTotalPages())
                .build();
    }

    public IncidentDetailResponseDTO toDetail(IncidentDetailViewDTO view) {
        if (view == null) {
            return null;
        }
        return IncidentDetailResponseDTO.builder()
                .incident(toListItem(view.getIncident()))
                .aiRootCause(view.getAiRootCause())
                .aiSummary(view.getAiSummary())
                .aiRecommendedAction(view.getAiRecommendedAction())
                .investigationSource(view.getInvestigationSource())
                .correlationId(view.getCorrelationId())
                .healthyStreak(view.getHealthyStreak())
                .capturedLogsSnippet(view.getCapturedLogsSnippet())
                .evidence(view.getEvidence() == null ? List.of() : view.getEvidence().stream()
                        .map(e -> IncidentDetailResponseDTO.EvidenceDTO.builder()
                                .id(e.getId()).kind(e.getKind()).payloadJson(e.getPayloadJson()).createdAt(e.getCreatedAt())
                                .build())
                        .toList())
                .suggestions(view.getSuggestions() == null ? List.of() : view.getSuggestions().stream()
                        .map(s -> IncidentDetailResponseDTO.SuggestionDTO.builder()
                                .id(s.getId()).actionType(s.getActionType()).label(s.getLabel())
                                .risk(s.getRisk()).enabled(s.isEnabled())
                                .disabledReason(s.getDisabledReason()).aiRationale(s.getAiRationale())
                                .payloadJson(s.getPayloadJson())
                                .build())
                        .toList())
                .executions(view.getExecutions() == null ? List.of() : view.getExecutions().stream()
                        .map(x -> IncidentDetailResponseDTO.ExecutionDTO.builder()
                                .id(x.getId()).suggestionId(x.getSuggestionId()).actorUserId(x.getActorUserId())
                                .outcome(x.getOutcome()).errorMessage(x.getErrorMessage()).createdAt(x.getCreatedAt())
                                .build())
                        .toList())
                .deliveries(view.getDeliveries() == null ? List.of() : view.getDeliveries().stream()
                        .map(d -> IncidentDetailResponseDTO.DeliveryDTO.builder()
                                .email(d.getEmail()).outcome(d.getOutcome()).kind(d.getKind()).sentAt(d.getSentAt())
                                .build())
                        .toList())
                .timeline(view.getTimeline() == null ? List.of() : view.getTimeline().stream()
                        .map(t -> IncidentDetailResponseDTO.TimelineDTO.builder()
                                .eventType(t.getEventType()).detail(t.getDetail()).createdAt(t.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }

    public IncidentActionExecutionResponseDTO toExecution(IncidentActionExecutionViewDTO view) {
        if (view == null) {
            return null;
        }
        return IncidentActionExecutionResponseDTO.builder()
                .id(view.getId())
                .incidentId(view.getIncidentId())
                .suggestionId(view.getSuggestionId())
                .actorUserId(view.getActorUserId())
                .actorEmail(view.getActorEmail())
                .actorRole(view.getActorRole())
                .correlationId(view.getCorrelationId())
                .outcome(view.getOutcome())
                .beforeJson(view.getBeforeJson())
                .afterJson(view.getAfterJson())
                .errorMessage(view.getErrorMessage())
                .createdAt(view.getCreatedAt())
                .build();
    }
}
