package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.LifecycleEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentLifecycleEvent {

    private UUID id;

    private UUID incidentId;

    private LifecycleEventType eventType;

    private String detail;

    private String correlationId;

    private LocalDateTime createdAt;
}
