package com.keepguard.ms_ai_guardian.adapters.out.notification;

import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.application.port.out.cache.RateLimiterPort;
import com.keepguard.ms_ai_guardian.application.port.out.notification.NotificationKind;
import com.keepguard.ms_ai_guardian.application.port.out.notification.NotificationPort;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.i18n.GuardianPortuguese;
import com.keepguard.ms_ai_guardian.infrastructure.template.EmailTemplateRenderer;
import com.keepguard.ms_ai_guardian.infrastructure.template.PlaceholderRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService implements NotificationPort {

    private final RabbitTemplate rabbitTemplate;
    private final GuardianAuditPublisher auditPublisher;
    private final EmailTemplateRenderer templates;
    private final GuardianProperties properties;
    private final RateLimiterPort rateLimiter;
    private final org.springframework.web.client.RestClient restClient = org.springframework.web.client.RestClient.create();

    @Value("${app.communication.url:http://ms-communication:8082}")
    private String communicationUrl;

    @Value("${app.rabbitmq.email-exchange:srv-email-google-sender-exchange-dev}")
    private String emailExchange;

    @Value("${app.rabbitmq.email-routing-key:email.google.send}")
    private String emailRoutingKey;

    @Override
    public boolean send(NotificationCommand command) {
        String html = templates.render(command.kind(), command.variables());
        return dispatchEmail(command.subject(), html, command.serviceName(),
                command.logContext(), command.correlationId());
    }

    @Override
    public boolean sendHtmlTo(String recipient, String subject, String htmlBody, String serviceName,
            String logContext, UUID correlationId, boolean publishAudit) {
        String cid = correlationId != null
                ? correlationId.toString()
                : UUID.nameUUIDFromBytes((serviceName + "|" + logContext + "|" + subject + "|" + recipient)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        boolean published = publishToGoogleSender(recipient, subject, htmlBody, logContext, cid);
        if (publishAudit) {
            auditPublisher.publish(
                    published ? "GUARDIAN_ALERT_SENT" : "GUARDIAN_ALERT_FAILED",
                    published ? "SUCCESS" : "FAILURE",
                    cid,
                    "INCIDENT",
                    serviceName);
        }
        return published;
    }

    public boolean sendIncidentDiagnosticEmail(DiagnosticResultDTO result) {
        String color = switch (result.getSeverity()) {
            case CRITICAL -> "#dc2626";
            case HIGH -> "#ea580c";
            case MEDIUM -> "#d97706";
            default -> "#2563eb";
        };
        String subject = String.format("🚨 [KeepGuard AI Guardian] Incidente: %s (%s)",
                result.getServiceName(), GuardianPortuguese.severity(result.getSeverity()));
        return send(new NotificationCommand(
                NotificationKind.INCIDENT_DIAGNOSTIC,
                subject,
                Map.of(
                        "headerColor", color,
                        "serviceName", nvl(result.getServiceName()),
                        "podName", nvl(result.getPodName()),
                        "severity", GuardianPortuguese.severity(result.getSeverity()),
                        "rootCause", PlaceholderRenderer.html(result.getRootCause()),
                        "recommendedAction", PlaceholderRenderer.html(result.getRecommendedAction()),
                        "generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                ),
                result.getServiceName(),
                result.getPodName(),
                result.getIncidentId()
        ));
    }

    public boolean sendPrOpenedEmail(PullRequestLifecycle pr, DiagnosticResultDTO incident) {
        String prUrl = prUrl(pr);
        String subject = String.format("🛠️ [AI Guardian] PR #%d aberto: %s", pr.getPrNumber(), pr.getRepoName());
        return send(new NotificationCommand(
                NotificationKind.PR_OPENED,
                subject,
                Map.of(
                        "headerColor", "#0f766e",
                        "repoName", nvl(pr.getRepoName()),
                        "prNumber", String.valueOf(pr.getPrNumber()),
                        "branchName", nvl(pr.getBranchName()),
                        "errorReason", GuardianPortuguese.errorReason(incident != null ? incident.getErrorReason() : null),
                        "rootCause", PlaceholderRenderer.html(incident != null ? incident.getRootCause() : null),
                        "filePath", nvl(pr.getFilePath()),
                        "recommendedAction", PlaceholderRenderer.html(incident != null ? incident.getRecommendedAction() : null),
                        "prUrl", prUrl
                ),
                pr.getRepoName(),
                pr.getRepoName(),
                null
        ));
    }

    public boolean sendPrReadyForHumanApprovalEmail(PullRequestLifecycle pr, String aiFeedback) {
        String subject = String.format("🤖 [AI Guardian Review] PR #%d Pronto para sua Aprovação (%s)",
                pr.getPrNumber(), pr.getRepoName());
        return send(new NotificationCommand(
                NotificationKind.PR_READY_FOR_APPROVAL,
                subject,
                Map.of(
                        "headerColor", "#2563eb",
                        "prNumber", String.valueOf(pr.getPrNumber()),
                        "repoName", nvl(pr.getRepoName()),
                        "aiFeedback", PlaceholderRenderer.html(aiFeedback),
                        "prUrl", prUrl(pr)
                ),
                pr.getRepoName(),
                pr.getRepoName(),
                null
        ));
    }

    public boolean sendCommentRepliedEmail(PullRequestLifecycle pr, String author, String userComment,
            String agentResponse, boolean generatedCommit) {
        String subject = String.format("💬 [CoderAgent] Resposta ao Comentário no PR #%d (%s)",
                pr.getPrNumber(), pr.getRepoName());
        String badge = generatedCommit
                ? "<span style='background: #dcfce7; color: #15803d; padding: 4px 10px; border-radius: 9999px; font-weight: 600; font-size: 12px;'>✅ Alteração Aplicada & Commit Gerado</span>"
                : "<span style='background: #e0f2fe; color: #0369a1; padding: 4px 10px; border-radius: 9999px; font-weight: 600; font-size: 12px;'>ℹ️ Esclarecimento Técnico (Sem Commit)</span>";
        return send(new NotificationCommand(
                NotificationKind.COMMENT_REPLIED,
                subject,
                Map.of(
                        "headerColor", "#0284c7",
                        "repoName", nvl(pr.getRepoName()),
                        "prNumber", String.valueOf(pr.getPrNumber()),
                        "author", nvl(author),
                        "userComment", PlaceholderRenderer.html(userComment),
                        "badgeHtml", badge,
                        "agentResponse", PlaceholderRenderer.html(agentResponse),
                        "prUrl", prUrl(pr)
                ),
                pr.getRepoName(),
                pr.getRepoName(),
                null
        ));
    }

    public boolean sendDeployStartedEmail(PullRequestLifecycle pr, String mergedBy) {
        String subject = String.format("⏳ [AI Guardian Deploy] Iniciando Rollout do %s no Kubernetes", pr.getRepoName());
        return send(new NotificationCommand(
                NotificationKind.DEPLOY_STARTED,
                subject,
                Map.of(
                        "headerColor", "#d97706",
                        "prNumber", String.valueOf(pr.getPrNumber()),
                        "mergedBy", nvl(mergedBy),
                        "repoName", nvl(pr.getRepoName())
                ),
                pr.getRepoName(),
                pr.getRepoName(),
                null
        ));
    }

    public boolean sendDeployCompletedEmail(PullRequestLifecycle pr, String mergedBy) {
        String subject = String.format("🎉 [AI Guardian Deploy] Hotfix do %s Publicado no Kubernetes!", pr.getRepoName());
        return send(new NotificationCommand(
                NotificationKind.DEPLOY_COMPLETED,
                subject,
                Map.of(
                        "headerColor", "#16a34a",
                        "prNumber", String.valueOf(pr.getPrNumber()),
                        "mergedBy", nvl(mergedBy),
                        "repoName", nvl(pr.getRepoName())
                ),
                pr.getRepoName(),
                pr.getRepoName(),
                null
        ));
    }

    public boolean sendDataInconsistencyEmail(String serviceName, String summary, String businessContext,
            String suggestedSql) {
        String subject = String.format("⚠️ [BusinessAnalystAgent] Inconsistência de Dados / Regra de Negócio em %s",
                serviceName);
        String sqlBlock = (suggestedSql != null && !suggestedSql.isBlank())
                ? "<div style='background: #1e293b; color: #f8fafc; padding: 14px; border-radius: 6px; font-family: monospace; font-size: 13px; overflow-x: auto; margin-top: 12px;'>"
                + PlaceholderRenderer.html(suggestedSql) + "</div>"
                : "";
        return send(new NotificationCommand(
                NotificationKind.DATA_INCONSISTENCY,
                subject,
                Map.of(
                        "headerColor", "#d97706",
                        "serviceName", nvl(serviceName),
                        "summary", nvl(summary),
                        "businessContext", PlaceholderRenderer.html(businessContext),
                        "sqlBlock", sqlBlock
                ),
                serviceName,
                serviceName,
                null
        ));
    }

    public boolean sendInfrastructureAlertEmail(String serviceName, String summary, String context,
            String suggestedAction) {
        String subject = String.format("⚙️ [KeepGuard AI Guardian] Alerta Operacional / Infraestrutura em %s",
                serviceName);
        return send(new NotificationCommand(
                NotificationKind.INFRASTRUCTURE_ALERT,
                subject,
                Map.of(
                        "headerColor", "#475569",
                        "serviceName", nvl(serviceName),
                        "summary", nvl(summary),
                        "context", PlaceholderRenderer.html(context),
                        "suggestedAction", nvl(suggestedAction)
                ),
                serviceName,
                serviceName,
                null
        ));
    }

    public String renderMesa(Map<String, String> variables) {
        return templates.render(NotificationKind.MESA, variables);
    }

    private boolean dispatchEmail(String subject, String htmlBody, String serviceName, String logContext,
            UUID stableCorrelationId) {
        String correlationId = stableCorrelationId != null
                ? stableCorrelationId.toString()
                : UUID.nameUUIDFromBytes((serviceName + "|" + logContext + "|" + subject)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        boolean published = publishToGoogleSender(properties.getDefaultRecipient(), subject, htmlBody, logContext,
                correlationId);
        auditPublisher.publish(
                published ? "GUARDIAN_ALERT_SENT" : "GUARDIAN_ALERT_FAILED",
                published ? "SUCCESS" : "FAILURE",
                correlationId,
                "INCIDENT",
                serviceName);
        if (published) {
            return true;
        }
        return fallbackHttp(subject, htmlBody, serviceName, logContext, correlationId);
    }

    private boolean fallbackHttp(String subject, String htmlBody, String serviceName, String logContext,
            String correlationId) {
        log.warn("Fallback HTTP ms-communication | contexto: {}", logContext);
        Map<String, Object> communicationPayload = new HashMap<>();
        communicationPayload.put("companyId", properties.getTenantId());
        communicationPayload.put("correlationId", correlationId);
        communicationPayload.put("xCorrelationId", correlationId);
        communicationPayload.put("messageType", "EMAIL");
        communicationPayload.put("recipient", properties.getDefaultRecipient());
        communicationPayload.put("templateType", "ALERTA_SEGURANCA");
        communicationPayload.put("subject", subject);
        communicationPayload.put("communicationType", "EMAIL");
        communicationPayload.put("codeUser", "ADMIN_GUARDIAN");
        communicationPayload.put("variables", Map.of(
                "serviceName", serviceName,
                "diagnosticReportHtml", htmlBody));
        try {
            restClient.post()
                    .uri(communicationUrl + "/api/v1/messages/send")
                    .header("X-Company-Id", properties.getTenantId())
                    .header("X-Correlation-ID", correlationId)
                    .header("Content-Type", "application/json")
                    .body(communicationPayload)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception httpEx) {
            log.error("Falha no envio HTTP e no RabbitMQ direto: {}", httpEx.getMessage());
            return false;
        }
    }

    private boolean publishToGoogleSender(String recipient, String subject, String htmlBody, String logContext,
            String correlationId) {
        try {
            rateLimiter.acquireEmailPermit();
            String to = recipient == null || recipient.isBlank() ? properties.getDefaultRecipient() : recipient;
            Map<String, Object> payload = new HashMap<>();
            payload.put("tenant_id", properties.getTenantId());
            payload.put("x_correlation_id", correlationId);
            payload.put("correlationId", correlationId);
            payload.put("to", to);
            payload.put("subject", subject);
            payload.put("html", htmlBody);
            log.info("Publicando e-mail via RabbitMQ ({}/{}) | contexto: {}", emailExchange, emailRoutingKey, logContext);
            rabbitTemplate.convertAndSend(emailExchange, emailRoutingKey, payload);
            return true;
        } catch (Exception e) {
            log.error("Falha ao publicar e-mail no RabbitMQ: {}", e.getMessage(), e);
            return false;
        }
    }

    private String prUrl(PullRequestLifecycle pr) {
        if (pr.getPrUrl() != null && !pr.getPrUrl().isBlank()) {
            return pr.getPrUrl();
        }
        return "https://github.com/keepguard/" + pr.getRepoName() + "/pull/" + pr.getPrNumber();
    }

    private static String nvl(String value) {
        return PlaceholderRenderer.nvl(value);
    }
}
