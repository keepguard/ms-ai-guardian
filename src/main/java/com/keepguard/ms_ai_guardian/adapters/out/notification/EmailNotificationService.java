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
    private final org.springframework.web.client.RestClient restClient = org.springframework.web.client.RestClient.create();

    @Value("${app.communication.url:http://ms-communication:8082}")
    private String communicationUrl;

    @Value("${app.rabbitmq.email-exchange:srv-email-google-sender-exchange-dev}")
    private String emailExchange;

    @Value("${app.rabbitmq.email-routing-key:email.google.send}")
    private String emailRoutingKey;

    @Value("${app.guardian.default-recipient:rafael.nogueira2009@gmail.com}")
    private String defaultRecipient;

    @Value("${app.guardian.tenant-id:f7fc7350-b9fc-4e54-9c58-ac9385b23ae3}")
    private String defaultTenantId;

    public boolean sendIncidentDiagnosticEmail(DiagnosticResultDTO result) {
        String subject = String.format("🚨 [KeepGuard AI Guardian] Incidente: %s (%s)",
                result.getServiceName(), result.getSeverity());
        return dispatchEmail(subject, buildHtmlReport(result), result.getServiceName(),
                result.getSeverity().name(), result.getPodName());
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

    public boolean sendPrReadyForHumanApprovalEmail(com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle pr, String aiFeedback) {
        String recipient = defaultRecipient;
        String subject = String.format("🤖 [AI Guardian Review] PR #%d Pronto para sua Aprovação (%s)", pr.getPrNumber(), pr.getRepoName());

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: #2563eb; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                .content { padding: 24px; }
                .btn { display: inline-block; padding: 12px 24px; background: #16a34a; color: #ffffff !important; text-decoration: none; border-radius: 8px; font-weight: 700; margin-top: 16px; }
                .box { background: #f8fafc; border-left: 4px solid #2563eb; padding: 14px 16px; margin: 16px 0; border-radius: 0 8px 8px 0; font-size: 14px; }
                .footer { background: #f1f5f9; padding: 16px; text-align: center; font-size: 12px; color: #64748b; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>🧐 ReviewerAgent Aprovou o Hotfix!</h1>
                  <p>O PR #%d do serviço <strong>%s</strong> passou na revisão da IA e aguarda seu Merge.</p>
                </div>
                <div class="content">
                  <p>Olá Rafael,</p>
                  <p>O <strong>CoderAgent</strong> abriu o Pull Request e o <strong>ReviewerAgent</strong> realizou a análise técnica de segurança e arquitetura.</p>
                  
                  <div class="box">
                    <strong>Parecer do ReviewerAgent:</strong><br/>
                    %s
                  </div>

                  <p>Para concluir a aplicação do hotfix e disparar o deploy no Kubernetes, faça a revisão e o merge no GitHub:</p>
                  <a href="%s" class="btn" target="_blank">🔗 Acessar Pull Request #%d no GitHub</a>
                </div>
                <div class="footer">
                  KeepGuard Multi-Agent System • Quality Gate Humano Obrigatório
                </div>
              </div>
            </body>
            </html>
            """,
                pr.getPrNumber(), pr.getRepoName(),
                aiFeedback.replace("\n", "<br/>"),
                pr.getPrUrl() != null ? pr.getPrUrl() : "https://github.com/keepguard/" + pr.getRepoName() + "/pull/" + pr.getPrNumber(),
                pr.getPrNumber()
        );

        return sendGenericEmail(subject, html, pr.getRepoName());
    }

    public boolean sendCommentRepliedEmail(com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle pr, String author, String userComment, String agentResponse, boolean generatedCommit) {
        String subject = String.format("💬 [CoderAgent] Resposta ao Comentário no PR #%d (%s)", pr.getPrNumber(), pr.getRepoName());

        String badge = generatedCommit 
                ? "<span style='background: #dcfce7; color: #15803d; padding: 4px 10px; border-radius: 9999px; font-weight: 600; font-size: 12px;'>✅ Alteração Aplicada & Commit Gerado</span>"
                : "<span style='background: #e0f2fe; color: #0369a1; padding: 4px 10px; border-radius: 9999px; font-weight: 600; font-size: 12px;'>ℹ️ Esclarecimento Técnico (Sem Commit)</span>";

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: #0284c7; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                .content { padding: 24px; font-size: 14px; line-height: 1.6; }
                .quote-box { background: #f8fafc; border-left: 4px solid #94a3b8; padding: 12px 16px; margin: 12px 0; border-radius: 0 6px 6px 0; font-style: italic; }
                .reply-box { background: #f0fdf4; border-left: 4px solid #22c55e; padding: 14px 16px; margin: 12px 0; border-radius: 0 6px 6px 0; }
                .btn { display: inline-block; background: #0284c7; color: #ffffff !important; padding: 12px 24px; border-radius: 6px; text-decoration: none; font-weight: 600; margin-top: 16px; }
                .footer { background: #f1f5f9; padding: 16px; text-align: center; font-size: 12px; color: #64748b; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>🤖 CoderAgent Respondeu ao seu Feedback</h1>
                  <p>Repositório: %s • Pull Request #%d</p>
                </div>
                <div class="content">
                  <p>Olá Rafael,</p>
                  <p>O <strong>CoderAgent</strong> processou seu feedback de Code Review enviado por <strong>@%s</strong>:</p>
                  
                  <div class="quote-box">
                    <strong>Seu Comentário:</strong><br/>
                    "%s"
                  </div>

                  <div style="margin: 16px 0;">
                    %s
                  </div>

                  <div class="reply-box">
                    <strong>Resposta do CoderAgent:</strong><br/>
                    %s
                  </div>

                  <a href="%s" class="btn">Ver Thread no GitHub (PR #%d)</a>
                </div>
                <div class="footer">
                  KeepGuard Multi-Agent System • Code Review Interativo
                </div>
              </div>
            </body>
            </html>
            """,
                pr.getRepoName(), pr.getPrNumber(),
                author,
                userComment.replace("\n", "<br/>"),
                badge,
                agentResponse.replace("\n", "<br/>"),
                pr.getPrUrl() != null ? pr.getPrUrl() : "https://github.com/keepguard/" + pr.getRepoName() + "/pull/" + pr.getPrNumber(),
                pr.getPrNumber()
        );

        return sendGenericEmail(subject, html, pr.getRepoName());
    }

    public boolean sendDeployStartedEmail(com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle pr, String mergedBy) {
        String subject = String.format("⏳ [AI Guardian Deploy] Iniciando Rollout do %s no Kubernetes", pr.getRepoName());

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: #d97706; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                .content { padding: 24px; font-size: 14px; line-height: 1.6; }
                .footer { background: #f1f5f9; padding: 16px; text-align: center; font-size: 12px; color: #64748b; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>⏳ DeployerAgent Assumiu o Deploy</h1>
                  <p>Iniciando esteira de rollout no Kubernetes</p>
                </div>
                <div class="content">
                  <p>Olá Rafael,</p>
                  <p>O Merge do PR #%d foi aprovado e efetuado por <strong>@%s</strong>.</p>
                  <p>O <strong>DeployerAgent</strong> foi disparado e está aplicando o rollout restart no cluster Kubernetes no namespace <code>keepguard</code>.</p>
                  <p>Serviço Alvo: <strong>%s</strong></p>
                  <p>Você receberá um novo e-mail assim que o pod estiver 100%% online e saudável.</p>
                </div>
                <div class="footer">
                  KeepGuard Multi-Agent Autonomous Pipeline • Deploy em Andamento
                </div>
              </div>
            </body>
            </html>
            """, pr.getPrNumber(), mergedBy, pr.getRepoName());

        return sendGenericEmail(subject, html, pr.getRepoName());
    }

    public boolean sendDeployCompletedEmail(com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle pr, String mergedBy) {
        String subject = String.format("🎉 [AI Guardian Deploy] Hotfix do %s Publicado no Kubernetes!", pr.getRepoName());

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: #16a34a; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                .content { padding: 24px; font-size: 14px; line-height: 1.6; }
                .footer { background: #f1f5f9; padding: 16px; text-align: center; font-size: 12px; color: #64748b; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>🚀 Hotfix em Produção com Sucesso!</h1>
                  <p>Deploy e Rollout concluídos no namespace KeepGuard.</p>
                </div>
                <div class="content">
                  <p>Olá Rafael,</p>
                  <p>O Merge do PR #%d foi efetuado por <strong>@%s</strong>.</p>
                  <p>O <strong>DeployerAgent</strong> assumiu o processo e aplicou o rollout com sucesso no cluster Kubernetes.</p>
                  <p>Status do Serviço <strong>%s</strong>: <span style="color: #16a34a; font-weight: 700;">● Online & Estável</span></p>
                </div>
                <div class="footer">
                  KeepGuard Multi-Agent Autonomous Pipeline • Ciclo Finalizado
                </div>
              </div>
            </body>
            </html>
            """, pr.getPrNumber(), mergedBy, pr.getRepoName());

        return sendGenericEmail(subject, html, pr.getRepoName());
    }

    public boolean sendDataInconsistencyEmail(
            String serviceName,
            String summary,
            String businessContext,
            String suggestedSql) {

        String subject = String.format("⚠️ [BusinessAnalystAgent] Inconsistência de Dados / Regra de Negócio em %s", serviceName);

        String sqlBlock = (suggestedSql != null && !suggestedSql.isBlank())
                ? String.format("<div style='background: #1e293b; color: #f8fafc; padding: 14px; border-radius: 6px; font-family: monospace; font-size: 13px; overflow-x: auto; margin-top: 12px;'>%s</div>",
                        suggestedSql.replace("\n", "<br/>").replace(" ", "&nbsp;"))
                : "";

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: #d97706; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                .content { padding: 24px; font-size: 14px; line-height: 1.6; }
                .box { background: #fffbeb; border-left: 4px solid #f59e0b; padding: 14px 16px; margin: 14px 0; border-radius: 0 6px 6px 0; }
                .footer { background: #f1f5f9; padding: 16px; text-align: center; font-size: 12px; color: #64748b; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>👔 Diagnóstico de Negócio & Banco de Dados</h1>
                  <p>Serviço: %s • Ação Operacional Recomendada</p>
                </div>
                <div class="content">
                  <p>Olá Rafael,</p>
                  <p>O <strong>BusinessAnalystAgent</strong> identificou que o erro ocorrido no serviço <strong>%s</strong> é decorrente de uma <strong>inconsistência de dados ou regra de negócio</strong> e <u>não de um defeito no código-fonte</u>.</p>
                  
                  <div class="box">
                    <strong>Motivo Identificado:</strong> %s<br/><br/>
                    <strong>Contexto Funcional:</strong><br/>
                    %s
                  </div>

                  <p>🛡️ <strong>Ação do Sistema:</strong> Nenhum Pull Request de código foi gerado desnecessariamente.</p>

                  <p><strong>Sugestão de Ação / Script SQL Corretivo:</strong></p>
                  %s
                </div>
                <div class="footer">
                  KeepGuard Multi-Agent System • Business & Database Intelligence
                </div>
              </div>
            </body>
            </html>
            """,
                serviceName, serviceName,
                summary,
                businessContext.replace("\n", "<br/>"),
                sqlBlock
        );

        return sendGenericEmail(subject, html, serviceName);
    }

    public boolean sendInfrastructureAlertEmail(
            String serviceName,
            String summary,
            String context,
            String suggestedAction) {

        String subject = String.format("⚙️ [KeepGuard AI Guardian] Alerta Operacional / Infraestrutura em %s", serviceName);

        String html = String.format("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <style>
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 20px; }
                .card { max-width: 650px; margin: 0 auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e2e8f0; }
                .header { background: #475569; color: #ffffff; padding: 24px; text-align: left; }
                .header h1 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                .content { padding: 24px; font-size: 14px; line-height: 1.6; }
                .box { background: #f1f5f9; border-left: 4px solid #64748b; padding: 14px 16px; margin: 14px 0; border-radius: 0 6px 6px 0; }
                .footer { background: #f1f5f9; padding: 16px; text-align: center; font-size: 12px; color: #64748b; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>⚙️ Incidente de Infraestrutura / Orquestração K8s</h1>
                  <p>Serviço: %s • Diagnóstico SRE / DevOps</p>
                </div>
                <div class="content">
                  <p>Olá Rafael,</p>
                  <p>O <strong>AI Guardian</strong> detectou um evento operacional a nível de container/Kubernetes no serviço <strong>%s</strong>.</p>
                  
                  <div class="box">
                    <strong>Diagnóstico:</strong> %s<br/><br/>
                    <strong>Contexto Operacional:</strong><br/>
                    %s
                  </div>

                  <p>🛡️ <strong>Ação do Sistema:</strong> Este incidente foi classificado como falha de infraestrutura/deploy. Nenhum Pull Request de código-fonte foi gerado desnecessariamente.</p>

                  <p><strong>Ação Recomendada:</strong></p>
                  <div style="background: #1e293b; color: #f8fafc; padding: 12px 16px; border-radius: 6px; font-family: monospace; font-size: 13px;">
                    %s
                  </div>
                </div>
                <div class="footer">
                  KeepGuard Multi-Agent System • SRE & Infrastructure Intelligence
                </div>
              </div>
            </body>
            </html>
            """,
                serviceName, serviceName,
                summary,
                context.replace("\n", "<br/>"),
                suggestedAction
        );

        return sendGenericEmail(subject, html, serviceName);
    }

    private boolean sendGenericEmail(String subject, String htmlBody, String serviceName) {
        return dispatchEmail(subject, htmlBody, serviceName, "INFO", serviceName);
    }

    /**
     * Publica direto no srv-email-google-sender (snake_case).
     * O HTTP do ms-communication devolve 200 ao enfileirar, mas o payload
     * camelCase/sem tenant_id é rejeitado pelo consumidor Python — não usar como sucesso.
     */
    private boolean dispatchEmail(String subject, String htmlBody, String serviceName, String severity, String logContext) {
        boolean published = publishToGoogleSender(subject, htmlBody, logContext);
        if (published) {
            return true;
        }

        log.warn("Fallback HTTP ms-communication | contexto: {}", logContext);
        Map<String, Object> communicationPayload = new HashMap<>();
        communicationPayload.put("tenantId", defaultTenantId);
        communicationPayload.put("xCorrelationId", UUID.randomUUID().toString());
        communicationPayload.put("messageType", "EMAIL");
        communicationPayload.put("recipient", defaultRecipient);
        communicationPayload.put("templateType", "ALERTA_SEGURANCA");
        communicationPayload.put("subject", subject);
        communicationPayload.put("communicationType", "EMAIL");
        communicationPayload.put("codeUser", "ADMIN_GUARDIAN");

        Map<String, Object> variables = new HashMap<>();
        variables.put("serviceName", serviceName);
        variables.put("severity", severity);
        variables.put("diagnosticReportHtml", htmlBody);
        communicationPayload.put("variables", variables);

        try {
            restClient.post()
                    .uri(communicationUrl + "/api/v1/messages/send")
                    .header("X-Tenant-Id", defaultTenantId)
                    .header("Content-Type", "application/json")
                    .body(communicationPayload)
                    .retrieve()
                    .toBodilessEntity();
            log.warn("HTTP ms-communication aceitou, mas a entrega Gmail depende do payload na fila. contexto: {}", logContext);
            return true;
        } catch (Exception httpEx) {
            log.error("Falha no envio HTTP e no RabbitMQ direto: {}", httpEx.getMessage());
            return false;
        }
    }

    private boolean publishToGoogleSender(String subject, String htmlBody, String logContext) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("tenant_id", defaultTenantId);
            payload.put("x_correlation_id", UUID.randomUUID().toString());
            payload.put("to", defaultRecipient);
            payload.put("subject", subject);
            payload.put("html", htmlBody);

            log.info("Publicando e-mail via RabbitMQ ({}/{}) | contexto: {}", emailExchange, emailRoutingKey, logContext);
            rabbitTemplate.convertAndSend(emailExchange, emailRoutingKey, payload);
            log.info("✅ E-mail publicado na fila do srv-email-google-sender para {}", defaultRecipient);
            return true;
        } catch (Exception e) {
            log.error("Falha ao publicar e-mail no RabbitMQ: {}", e.getMessage(), e);
            return false;
        }
    }
}
