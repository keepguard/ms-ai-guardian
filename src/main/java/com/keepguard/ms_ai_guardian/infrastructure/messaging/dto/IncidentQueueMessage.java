package com.keepguard.ms_ai_guardian.infrastructure.messaging.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentQueueMessage implements Serializable {
    private UUID trackingId;
    private String namespace;
    private String podName;
    private String serviceName;
    private String errorReason;
    private boolean forceSendEmail;
    private long enqueuedTimestamp;
}
