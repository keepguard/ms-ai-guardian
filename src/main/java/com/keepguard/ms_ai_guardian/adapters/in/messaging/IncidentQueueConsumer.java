package com.keepguard.ms_ai_guardian.adapters.in.messaging;

import com.keepguard.ms_ai_guardian.application.service.AiDiagnosticService;
import com.keepguard.ms_ai_guardian.infrastructure.messaging.RabbitMqTopologyConfig;
import com.keepguard.ms_ai_guardian.infrastructure.messaging.dto.IncidentQueueMessage;
import com.keepguard.ms_ai_guardian.application.port.out.cache.RateLimiterPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentQueueConsumer {

    private final AiDiagnosticService aiDiagnosticService;
    private final RateLimiterPort rateLimiterService;

    @RabbitListener(queues = RabbitMqTopologyConfig.GUARDIAN_INCIDENT_QUEUE)
    public void consumeIncident(IncidentQueueMessage message) {
        long queueWaitTimeMs = System.currentTimeMillis() - message.getEnqueuedTimestamp();
        log.info("📥 [IncidentQueueConsumer] Consumindo incidente da fila RabbitMQ (Tracking ID: {} | Latência na fila: {}ms | Pod: {})",
                message.getTrackingId(), queueWaitTimeMs, message.getPodName());

        try {
            // ⏱️ Controle de Vazão Token Bucket antes de acionar a Squad
            rateLimiterService.acquireAiPromptPermit();

            aiDiagnosticService.diagnosePod(
                    message.getNamespace(),
                    message.getPodName(),
                    message.getServiceName(),
                    message.getErrorReason(),
                    message.isForceSendEmail()
            );

            log.info("✅ [IncidentQueueConsumer] Incidente {} processado com sucesso pela Squad Multi-Agent", message.getTrackingId());

        } catch (Exception e) {
            log.error("❌ [IncidentQueueConsumer] Erro ao processar incidente {}: {}", message.getTrackingId(), e.getMessage(), e);
            throw e; // Lança exceção para acionar a Dead Letter Queue (DLQ) se exceder tentativas
        }
    }
}
