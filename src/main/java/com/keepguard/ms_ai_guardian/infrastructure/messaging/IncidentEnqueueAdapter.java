package com.keepguard.ms_ai_guardian.infrastructure.messaging;

import com.keepguard.ms_ai_guardian.application.dto.DiagnoseCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticEnqueueViewDTO;
import com.keepguard.ms_ai_guardian.application.port.out.messaging.IncidentEnqueuePort;
import com.keepguard.ms_ai_guardian.infrastructure.messaging.dto.IncidentQueueMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class IncidentEnqueueAdapter implements IncidentEnqueuePort {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public DiagnosticEnqueueViewDTO enqueue(DiagnoseCommandDTO command) {
        var queueMsg = IncidentQueueMessage.builder()
                .trackingId(UUID.randomUUID())
                .namespace(command.getNamespace())
                .podName(command.getPodName())
                .serviceName(command.getServiceName())
                .errorReason(command.getErrorReason())
                .forceSendEmail(command.isForceSendEmail())
                .enqueuedTimestamp(System.currentTimeMillis())
                .build();
        rabbitTemplate.convertAndSend(
                RabbitMqTopologyConfig.GUARDIAN_INCIDENT_EXCHANGE,
                RabbitMqTopologyConfig.GUARDIAN_INCIDENT_ROUTING_KEY,
                queueMsg);
        return DiagnosticEnqueueViewDTO.builder()
                .trackingId(queueMsg.getTrackingId())
                .namespace(queueMsg.getNamespace())
                .podName(queueMsg.getPodName())
                .serviceName(queueMsg.getServiceName())
                .errorReason(queueMsg.getErrorReason())
                .forceSendEmail(queueMsg.isForceSendEmail())
                .enqueuedTimestamp(queueMsg.getEnqueuedTimestamp())
                .build();
    }
}
