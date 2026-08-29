package com.keepguard.ms_ai_guardian.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_invocations", schema = "ms_ai_guardian")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "prompt_key", length = 80)
    private String promptKey;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(length = 64)
    private String model;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
