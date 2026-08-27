package com.keepguard.ms_ai_guardian.application.service;

import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import com.keepguard.ms_ai_guardian.application.service.agents.BusinessAnalystAgentService;
import com.keepguard.ms_ai_guardian.application.service.agents.BusinessAnalystAgentService.VerdictType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiDiagnosticService {

    private final KubernetesInspectorService k8sInspector;
    private final IncidentRepository incidentRepository;
    private final EmailNotificationService emailNotificationService;
    private final com.keepguard.ms_ai_guardian.application.service.agents.BusinessAnalystAgentService businessAnalystAgentService;
    private final Optional<com.keepguard.ms_ai_guardian.application.service.agents.CoderAgentService> coderAgentService;
    private final Optional<com.keepguard.ms_ai_guardian.application.service.agents.ReviewerAgentService> reviewerAgentService;

    @Value("${app.guardian.anti-flapping-cooldown-minutes:15}")
    private int cooldownMinutes;

    @Value("${app.guardian.default-recipient:rafael.nogueira2009@gmail.com}")
    private String defaultRecipient;

    public DiagnosticResultDTO diagnosePod(String namespace, String podName, String serviceName, String errorReason, boolean forceSendEmail) {
        log.info("🤖 Iniciando diagnóstico inteligente para pod: {}/{} | Serviço: {}", namespace, podName, serviceName);

        // 1. Prevenção de Tempestade de Alertas (Anti-Flapping)
        if (!forceSendEmail) {
            LocalDateTime cooldownLimit = LocalDateTime.now().minusMinutes(cooldownMinutes);
            Optional<Incident> recentIncident = incidentRepository
                    .findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(podName, cooldownLimit);

            if (recentIncident.isPresent() && recentIncident.get().isNotificationSent()) {
                log.info("⏳ Incidente ignorado por cooldown anti-flapping para pod: {} (Já notificado recentemente)", podName);
                Incident inc = recentIncident.get();
                return DiagnosticResultDTO.builder()
                        .incidentId(inc.getId())
                        .podName(inc.getPodName())
                        .namespace(inc.getNamespace())
                        .serviceName(inc.getServiceName())
                        .severity(inc.getSeverity())
                        .errorReason(inc.getErrorReason())
                        .rootCause(inc.getAiRootCauseAnalysis())
                        .recommendedAction(inc.getAiRecommendedAction())
                        .notificationSent(false)
                        .build();
            }
            if (recentIncident.isPresent()) {
                log.info("🔁 Cooldown ignorado para pod {}: incidente recente ainda sem notificação enviada.", podName);
            }
        }

        // 2. Coleta de Logs e Eventos do Pod
        String podHealth = k8sInspector.describePodHealth(namespace, podName);
        String recentLogs = k8sInspector.getPodLogs(namespace, podName, 20);
        List<String> warningEvents = k8sInspector.getRecentWarningEvents(namespace, podName);

        // 3. Avaliação da Severidade
        IncidentSeverity severity = evaluateSeverity(errorReason, recentLogs, podHealth);

        // 4. GERAÇÃO DE FINGERPRINT / ASSINATURA ÚNICA DO ERRO
        String fingerprint = generateIncidentFingerprint(serviceName, errorReason, recentLogs);

        // 5. Deduplicação e Agrupamento por Fingerprint
        Optional<Incident> existingIncidentOpt = incidentRepository.findFirstByFingerprintOrderByCreatedAtDesc(fingerprint);
        Incident incident;
        DiagnosticResultDTO resultDTO;

        if (existingIncidentOpt.isPresent()) {
            Incident existingIncident = existingIncidentOpt.get();
            existingIncident.setOccurrencesCount(existingIncident.getOccurrencesCount() + 1);
            existingIncident.setLastSeenAt(LocalDateTime.now());
            incidentRepository.save(existingIncident);

            if (!forceSendEmail && existingIncident.isNotificationSent()) {
                log.info("🛡️ [Incident Deduplication] Incidente já rastreado para o fingerprint '{}' (ID: {}). Ocorrência #{} incrementada sem criar novo PR ou spam de e-mails.",
                        fingerprint, existingIncident.getId(), existingIncident.getOccurrencesCount());

                return DiagnosticResultDTO.builder()
                        .incidentId(existingIncident.getId())
                        .podName(existingIncident.getPodName())
                        .namespace(existingIncident.getNamespace())
                        .serviceName(existingIncident.getServiceName())
                        .severity(existingIncident.getSeverity())
                        .errorReason(existingIncident.getErrorReason())
                        .rootCause(existingIncident.getAiRootCauseAnalysis())
                        .recommendedAction(existingIncident.getAiRecommendedAction())
                        .technicalDetails(warningEvents)
                        .notificationSent(false)
                        .build();
            }

            log.info("🔁 Reprocessando incidente {} (fingerprint {}, forceSendEmail={}, notificationSent={}) para reenvio de notificação.",
                    existingIncident.getId(), fingerprint, forceSendEmail, existingIncident.isNotificationSent());
            incident = existingIncident;
            resultDTO = DiagnosticResultDTO.builder()
                    .incidentId(existingIncident.getId())
                    .podName(existingIncident.getPodName())
                    .namespace(existingIncident.getNamespace())
                    .serviceName(existingIncident.getServiceName())
                    .severity(existingIncident.getSeverity())
                    .errorReason(existingIncident.getErrorReason())
                    .rootCause(existingIncident.getAiRootCauseAnalysis())
                    .recommendedAction(existingIncident.getAiRecommendedAction())
                    .technicalDetails(warningEvents)
                    .notificationSent(false)
                    .build();
        } else {
            // 6. E-mail NUNCA espera o LLM. Heurística imediata; Ollama/API só depois, na squad.
            String aiResponse = generateHeuristicAnalysis(serviceName, errorReason, recentLogs, podHealth);
            String[] parsedAnalysis = parseAiAnalysis(aiResponse);
            String rootCause = parsedAnalysis[0];
            String recommendedAction = parsedAnalysis[1];

            // 7. Persistência do Novo Incidente
            incident = Incident.builder()
                    .podName(podName)
                    .namespace(namespace)
                    .serviceName(serviceName)
                    .severity(severity)
                    .errorReason(errorReason)
                    .fingerprint(fingerprint)
                    .occurrencesCount(1)
                    .lastSeenAt(LocalDateTime.now())
                    .capturedLogsSnippet(recentLogs.length() > 3000 ? recentLogs.substring(recentLogs.length() - 3000) : recentLogs)
                    .aiRootCauseAnalysis(rootCause)
                    .aiRecommendedAction(recommendedAction)
                    .status(IncidentStatus.DETECTED)
                    .targetRecipientEmail(defaultRecipient)
                    .notificationSent(false)
                    .build();

            incident = incidentRepository.save(incident);

            // 8. Monta DTO de Resposta
            resultDTO = DiagnosticResultDTO.builder()
                    .incidentId(incident.getId())
                    .podName(podName)
                    .namespace(namespace)
                    .serviceName(serviceName)
                    .severity(severity)
                    .errorReason(errorReason)
                    .rootCause(rootCause)
                    .recommendedAction(recommendedAction)
                    .technicalDetails(warningEvents)
                    .notificationSent(false)
                    .build();
        }

        // 9. 👔 CONSULTA AO BUSINESS ANALYST AGENT: Avalia se é infraestrutura/deploy, inconsistência de dados ou defeito de código
        var businessVerdict = businessAnalystAgentService.evaluateIncident(resultDTO, recentLogs);

        // Se for falha de infraestrutura/deploy -> NÃO cria PR de código e envia relatório SRE
        if (businessVerdict.type() == VerdictType.INFRASTRUCTURE_FAULT) {
            log.info("⚙️ [BusinessAnalystAgent] Falha classificada como INFRASTRUCTURE_FAULT. NÃO será aberto PR de código.");
            boolean emailSent = emailNotificationService.sendInfrastructureAlertEmail(
                    serviceName,
                    businessVerdict.summary(),
                    businessVerdict.businessContext(),
                    businessVerdict.suggestedSqlAction()
            );
            markIncidentNotified(incident, resultDTO, emailSent);
            return resultDTO;
        }

        // Se for inconsistência de banco/cadastro -> NÃO cria PR de código e envia relatório funcional
        if (!businessVerdict.requiresCodePr()) {
            log.info("👔 [BusinessAnalystAgent] Falha classificada como {}. NÃO será aberto PR de código.", businessVerdict.type());
            boolean emailSent = emailNotificationService.sendDataInconsistencyEmail(
                    serviceName,
                    businessVerdict.summary(),
                    businessVerdict.businessContext(),
                    businessVerdict.suggestedSqlAction()
            );
            markIncidentNotified(incident, resultDTO, emailSent);
            return resultDTO;
        }

        // 10. Envio do E-mail de Diagnóstico Padrão de Engenharia (antes de qualquer LLM)
        boolean emailSent = emailNotificationService.sendIncidentDiagnosticEmail(resultDTO);
        markIncidentNotified(incident, resultDTO, emailSent);

        // 11. Squad autônoma (PR) — LLM só depois do alerta. Falha/timeout não reabre o e-mail.
        if (coderAgentService.isPresent() && reviewerAgentService.isPresent() && !serviceName.contains("deployment") && !serviceName.contains("busybox")) {
            try {
                log.info("🛠️ Acionando CoderAgent para criar branch e Pull Request rico de hotfix...");
                String filePath = "src/main/resources/application.yml";
                if ("mock-sms-gateway".equalsIgnoreCase(serviceName)) {
                    filePath = "internal/core/service/sms_service.go";
                }
                var prOpt = coderAgentService.get().createHotfixPullRequest(resultDTO, filePath, recentLogs, businessVerdict);
                prOpt.ifPresent(reviewerAgentService.get()::performReview);
            } catch (Exception e) {
                log.error("Falha ao executar pipeline Multi-Agent de hotfix: {}", e.getMessage());
            }
        }

        log.info("✅ Diagnóstico concluído com sucesso para pod: {} | E-mail enviado: {}", podName, emailSent);
        return resultDTO;
    }

    private String generateIncidentFingerprint(String serviceName, String errorReason, String recentLogs) {
        String normalizedError = errorReason != null ? errorReason.trim().toLowerCase() : "unknown";
        String normalizedService = serviceName != null ? serviceName.trim().toLowerCase() : "unknown";
        
        // Identifica a linha de erro/função no log se disponível
        String location = "general";
        if (recentLogs != null && recentLogs.contains("sms_handler.go")) {
            location = "sms_handler.go:CalculateDiscountRate";
        } else if (recentLogs != null && recentLogs.contains("NullPointerException")) {
            location = "NullPointerException";
        }

        return org.springframework.util.DigestUtils.md5DigestAsHex(
                (normalizedService + ":" + normalizedError + ":" + location).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private String generateHeuristicAnalysis(String serviceName, String errorReason, String logs, String podHealth) {
        String cause = (errorReason != null && !errorReason.isBlank()) ? errorReason : "Instabilidade detectada nos logs da aplicação";

        return """
            [CAUSA_RAIZ]
            %s no microsserviço %s.
            [PLANO_DE_ACAO]
            1. Inspecionar logs e rastreabilidade da requisição no pod %s.
            2. Analisar o fluxo de execução e aplicar patch defensivo ou regularização de dados.
            """.formatted(cause, serviceName, serviceName);
    }

    private String[] parseAiAnalysis(String rawResponse) {
        String rootCause = "Não foi possível identificar a causa raiz detalhada.";
        String recommendedAction = "Inspecionar os logs do pod e validar os serviços dependentes.";

        if (rawResponse != null && rawResponse.contains("[CAUSA_RAIZ]") && rawResponse.contains("[PLANO_DE_ACAO]")) {
            int causeStart = rawResponse.indexOf("[CAUSA_RAIZ]") + "[CAUSA_RAIZ]".length();
            int actionStart = rawResponse.indexOf("[PLANO_DE_ACAO]");
            rootCause = rawResponse.substring(causeStart, actionStart).trim();
            recommendedAction = rawResponse.substring(actionStart + "[PLANO_DE_ACAO]".length()).trim();
        } else if (rawResponse != null) {
            rootCause = rawResponse.trim();
        }

        return new String[]{rootCause, recommendedAction};
    }

    private void markIncidentNotified(Incident incident, DiagnosticResultDTO resultDTO, boolean emailSent) {
        if (!emailSent) {
            return;
        }
        incident.setStatus(IncidentStatus.NOTIFIED);
        incident.setNotificationSent(true);
        incident.setNotificationSentAt(LocalDateTime.now());
        incidentRepository.save(incident);
        resultDTO.setNotificationSent(true);
    }

    private IncidentSeverity evaluateSeverity(String errorReason, String logs, String podHealth) {
        if (podHealth.contains("OOMKilled") || logs.contains("OutOfMemoryError") || "CrashLoopBackOff".equalsIgnoreCase(errorReason)) {
            return IncidentSeverity.CRITICAL;
        }
        if (logs.contains("Connection refused") || logs.contains("HikariPool") || logs.contains("PSQLException")) {
            return IncidentSeverity.HIGH;
        }
        if (logs.contains("NullPointerException") || logs.contains("FeignException")) {
            return IncidentSeverity.HIGH;
        }
        return IncidentSeverity.MEDIUM;
    }
}
