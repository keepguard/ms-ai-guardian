package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.ActionRisk;
import com.keepguard.ms_ai_guardian.domain.enums.RemediationActionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "incident_action_suggestions", schema = "ms_ai_guardian")
public class IncidentActionSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private RemediationActionType actionType;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ActionRisk risk;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "disabled_reason", columnDefinition = "TEXT")
    private String disabledReason;

    @Column(name = "ai_rationale", columnDefinition = "TEXT")
    private String aiRationale;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
