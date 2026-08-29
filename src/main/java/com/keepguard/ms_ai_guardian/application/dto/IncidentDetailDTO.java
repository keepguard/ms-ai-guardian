package com.keepguard.ms_ai_guardian.application.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class IncidentDetailDTO {
    private IncidentListItemDTO incident;
    private String aiRootCause;
    private String aiSummary;
    private String aiRecommendedAction;
    private String investigationSource;
    private String correlationId;
    private int healthyStreak;
    private String capturedLogsSnippet;
    private List<EvidenceDTO> evidence;
    private List<SuggestionDTO> suggestions;
    private List<ExecutionDTO> executions;
    private List<DeliveryDTO> deliveries;
    private List<TimelineDTO> timeline;

    @Data
    @Builder
    public static class EvidenceDTO {
        private UUID id;
        private String kind;
        private String payloadJson;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class SuggestionDTO {
        private UUID id;
        private String actionType;
        private String label;
        private String risk;
        private boolean enabled;
        private String disabledReason;
        private String aiRationale;
        private String payloadJson;
    }

    @Data
    @Builder
    public static class ExecutionDTO {
        private UUID id;
        private UUID suggestionId;
        private String actorUserId;
        private String outcome;
        private String errorMessage;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class DeliveryDTO {
        private String email;
        private String outcome;
        private String kind;
        private LocalDateTime sentAt;
    }

    @Data
    @Builder
    public static class TimelineDTO {
        private String eventType;
        private String detail;
        private LocalDateTime createdAt;
    }
}
