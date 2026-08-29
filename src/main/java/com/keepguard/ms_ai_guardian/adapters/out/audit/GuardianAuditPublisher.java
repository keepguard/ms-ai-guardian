package com.keepguard.ms_ai_guardian.adapters.out.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class GuardianAuditPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AtomicBoolean exchangeDeclared = new AtomicBoolean(false);

    @Value("${keepguard.audit.enabled:true}")
    private boolean enabled;

    @Value("${keepguard.audit.exchange:srv-audit-exchange-dev}")
    private String exchange;

    @Value("${keepguard.audit.routing-key:audit.event}")
    private String routingKey;

    public void publish(String action, String outcome, String correlationId, String resourceType, String resourceId) {
        if (!enabled) {
            return;
        }
        String cid = (correlationId == null || correlationId.isBlank())
                ? UUID.randomUUID().toString()
                : correlationId;
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("occurredAt", Instant.now().toString());
        event.put("schemaVersion", 1);
        event.put("sourceService", "ms-ai-guardian");
        event.put("correlationId", cid);
        event.put("action", action);
        event.put("outcome", outcome);
        event.put("actor", Map.of("type", "SYSTEM"));
        event.put("resource", Map.of("type", resourceType, "id", resourceId == null ? "" : resourceId));
        CompletableFuture.runAsync(() -> {
            try {
                declareExchangeOnce();
                rabbitTemplate.convertAndSend(exchange, routingKey, event, message -> {
                    message.getMessageProperties().setHeader("X-Correlation-ID", cid);
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                });
            } catch (Exception e) {
                log.warn("Falha ao publicar auditoria action={} correlationId={}: {}", action, cid, e.getMessage());
            }
        });
    }

    private void declareExchangeOnce() {
        if (exchangeDeclared.get()) {
            return;
        }
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(exchange, "topic", true);
            return null;
        });
        exchangeDeclared.set(true);
    }
}
