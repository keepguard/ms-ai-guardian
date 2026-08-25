package com.keepguard.ms_ai_guardian.adapters.out.notification;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange:ms-communication-exchange-dev}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key:communication.message.send}")
    private String routingKey;

    @Value("${app.guardian.default-recipient:rafael.nogueira2009@gmail.com}")
    private String defaultRecipient;

    @Value("${app.guardian.tenant-id:f7fc7350-b9fc-4e54-9c58-ac9385b23ae3}")
    private String defaultTenantId;

    public boolean sendIncidentDiagnosticEmail(DiagnosticResultDTO result) {
        try {
            String recipient = defaultRecipient;
            String subject = String.format("🚨 [KeepGuard AI Guardian] Incidente: %s (%s)",
                    result.getServiceName(), result.getSeverity());

            String htmlBody = buildHtmlReport(result);

            // Payload 100% aderente ao MessageSendRabbitMQDTO do ms-communication
            Map<String, Object> messagePayload = new HashMap<>();
            messagePayload.put("tenantId", defaultTenantId);
            messagePayload.put("xCorrelationId", UUID.randomUUID().toString());
            messagePayload.put("messageType", "SYSTEM_NOTIFICATION");
            messagePayload.put("recipient", recipient);
            messagePayload.put("templateType", "CADASTRO_SUCESSO"); // Template registrado
            messagePayload.put("subject", subject);
            messagePayload.put("content", htmlBody.length() > 950 ? htmlBody.substring(0, 950) : htmlBody);
            messagePayload.put("communicationType", "IMMEDIATE");
            messagePayload.put("codeUser", "ADMIN_GUARDIAN");

            Map<String, Object> variables = new HashMap<>();
            variables.put("userName", "Rafael Soares");
            variables.put("appName", "KeepGuard AI Guardian");
            variables.put("serviceName", result.getServiceName());
            variables.put("podName", result.getPodName());
            variables.put("severity", result.getSeverity().name());
            variables.put("errorReason", result.getErrorReason());
            variables.put("rootCause", result.getRootCause());
            variables.put("recommendedAction", result.getRecommendedAction());
            messagePayload.put("variables", variables);

            log.info("Publicando e-mail de diagnóstico via ms-communication para {} | Pod: {}", recipient, result.getPodName());
            rabbitTemplate.convertAndSend(exchange, routingKey, messagePayload);
            return true;

        } catch (Exception e) {
            log.error("Falha ao publicar e-mail de incidente no RabbitMQ: {}", e.getMessage(), e);
            return false;
        }
    }

    private String buildHtmlReport(DiagnosticResultDTO result) {
        String severityColor = switch (result.getSeverity()) {
            case CRITICAL -> "#dc2626"; // Vermelho escuro
            case HIGH -> "#ea580c";     // Laranja forte
            case MEDIUM -> "#d97706";   // Âmbar
            default -> "#2563eb";       // Azul
        };

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: %s; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 0; opacity: 0.9; font-size: 13px; }
                .content { padding: 24px; }
                .badge { display: inline-block; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 600; background: #f1f5f9; color: #334155; margin-right: 6px; }
                .section-title { font-size: 14px; text-transform: uppercase; font-weight: 700; color: #64748b; margin-top: 20px; margin-bottom: 8px; letter-spacing: 0.5px; }
                .box { background: #f8fafc; border-left: 4px solid %s; padding: 14px 16px; border-radius: 0 8px 8px 0; font-size: 14px; line-height: 1.6; margin-bottom: 16px; }
                .action-box { background: #f0fdf4; border-left: 4px solid #16a34a; padding: 14px 16px; border-radius: 0 8px 8px 0; font-size: 14px; line-height: 1.6; color: #166534; font-weight: 500; }
                .footer { background: #f1f5f9; padding: 16px 24px; font-size: 12px; color: #64748b; text-align: center; border-top: 1px solid #e2e8f0; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>🛡️ KeepGuard AI Guardian - Diagnóstico de Incidente</h1>
                  <p>Análise automatizada de causa raiz executada por IA</p>
                </div>
                <div class="content">
                  <div>
                    <span class="badge">Serviço: <strong>%s</strong></span>
                    <span class="badge">Pod: <strong>%s</strong></span>
                    <span class="badge" style="background: %s; color: white;">Severidade: <strong>%s</strong></span>
                  </div>

                  <div class="section-title">🔍 Causa Raiz Identificada</div>
                  <div class="box">
                    %s
                  </div>

                  <div class="section-title">💡 Plano de Ação Recomendado</div>
                  <div class="action-box">
                    %s
                  </div>
                </div>
                <div class="footer">
                  KeepGuard Observability & Self-Healing AI Agent • Gerado em %s
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                severityColor,
                severityColor,
                result.getServiceName(),
                result.getPodName(),
                severityColor,
                result.getSeverity().name(),
                result.getRootCause().replace("\n", "<br/>"),
                result.getRecommendedAction().replace("\n", "<br/>"),
                now
        );
    }
}
