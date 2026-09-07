package com.keepguard.ms_ai_guardian.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocation {

    private UUID id;

    private UUID incidentId;

    private String promptKey;

    private String promptVersion;

    private String model;

    private String inputHash;

    private String output;

    private Long latencyMs;

    private boolean fallbackUsed;

    private LocalDateTime createdAt;
}
