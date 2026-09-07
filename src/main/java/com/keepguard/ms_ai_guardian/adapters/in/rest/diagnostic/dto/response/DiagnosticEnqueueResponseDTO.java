package com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosticEnqueueResponseDTO {
    private UUID trackingId;
    private String namespace;
    private String podName;
    private String serviceName;
    private String errorReason;
    private boolean forceSendEmail;
    private long enqueuedTimestamp;
}
