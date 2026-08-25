package com.keepguard.ms_ai_guardian.application.service;

import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final Optional<ChatClient.Builder> chatClientBuilder;

    @Value("${app.guardian.anti-flapping-cooldown-minutes:15}")
    private int cooldownMinutes;

    @Value("${app.guardian.default-recipient:rafael.nogueira2009@gmail.com}")
    private String defaultRecipient;

    @Transactional
    public DiagnosticResultDTO diagnosePod(String namespace, String podName, String serviceName, String errorReason, boolean forceSendEmail) {
        log.info("🤖 Iniciando diagnóstico inteligente para pod: {}/{} | Serviço: {}", namespace, podName, serviceName);

        // 1. Prevenção de Tempestade de Alertas (Anti-Flapping)
        if (!forceSendEmail) {
            LocalDateTime cooldownLimit = LocalDateTime.now().minusMinutes(cooldownMinutes);
            Optional<Incident> recentIncident = incidentRepository
                    .findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(podName, cooldownLimit);

            if (recentIncident.isPresent()) {
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
        }

        // 2. Coleta de Evidências do Kubernetes
        String podHealth = k8sInspector.describePodHealth(namespace, podName);
        String recentLogs = k8sInspector.getPodLogs(namespace, podName, 80);
        List<String> warningEvents = k8sInspector.getRecentWarningEvents(namespace, podName);

        // 3. Execução do Raciocínio com IA (Ollama / Fallback Heurístico)
        String aiResponse = executeAiReasoning(serviceName, podName, errorReason, podHealth, recentLogs, warningEvents);

        // 4. Parse do Diagnóstico e Severidade
        IncidentSeverity severity = evaluateSeverity(errorReason, recentLogs, podHealth);
        String[] parsedAnalysis = parseAiAnalysis(aiResponse);
        String rootCause = parsedAnalysis[0];
        String recommendedAction = parsedAnalysis[1];

        // 5. Persistência no Banco de Dados (Schema ms_ai_guardian)
        Incident incident = Incident.builder()
                .namespace(namespace)
                .podName(podName)
                .serviceName(serviceName)
                .errorReason(errorReason != null ? errorReason : "UNKNOWN_ERROR")
                .severity(severity)
                .status(IncidentStatus.DIAGNOSED)
                .capturedLogsSnippet(recentLogs.length() > 4000 ? recentLogs.substring(0, 4000) : recentLogs)
                .aiRootCauseAnalysis(rootCause)
                .aiRecommendedAction(recommendedAction)
                .targetRecipientEmail(defaultRecipient)
                .notificationSent(false)
                .build();

        incident = incidentRepository.save(incident);

        // 6. Monta DTO de Resposta
        DiagnosticResultDTO resultDTO = DiagnosticResultDTO.builder()
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

        // 7. Envio do E-mail de Diagnóstico
        boolean emailSent = emailNotificationService.sendIncidentDiagnosticEmail(resultDTO);
        if (emailSent) {
            incident.setStatus(IncidentStatus.NOTIFIED);
            incident.setNotificationSent(true);
            incident.setNotificationSentAt(LocalDateTime.now());
            incidentRepository.save(incident);
            resultDTO.setNotificationSent(true);
        }

        log.info("✅ Diagnóstico concluído com sucesso para pod: {} | E-mail enviado: {}", podName, emailSent);
        return resultDTO;
    }

    private String executeAiReasoning(String serviceName, String podName, String errorReason,
                                      String podHealth, String recentLogs, List<String> warningEvents) {
        String systemPrompt = """
            Você é o KeepGuard AI Guardian, um engenheiro SRE e especialista em arquitetura de microsserviços Java Spring Boot e Kubernetes.
            Sua missão é analisar falhas, logs e eventos de containers para determinar a causa raiz exata e recomendar ações corretivas objetivas.
            
            DIRETRIZES:
            1. Seja direto, técnico e preciso. Responda SEMPRE em Português do Brasil.
            2. Estruture sua resposta estritamente nas seguintes seções:
               [CAUSA_RAIZ]
               Explicação técnica e detalhada do motivo da falha.
               [PLANO_DE_ACAO]
               Passos práticos para mitigar e resolver o problema definitivamente.
            """;

        String userPrompt = String.format("""
            Analise o seguinte incidente no cluster Kubernetes KeepGuard:
            
            Serviço: %s
            Pod: %s
            Motivo Reportado: %s
            
            --- STATUS DO POD ---
            %s
            
            --- EVENTOS DE WARNING ---
            %s
            
            --- ÚLTIMAS LINHAS DE LOG ---
            %s
            """,
                serviceName, podName, errorReason,
                podHealth,
                String.join("\n", warningEvents),
                recentLogs
        );

        try {
            if (chatClientBuilder.isPresent()) {
                ChatClient chatClient = chatClientBuilder.get().build();
                return chatClient.prompt(new Prompt(userPrompt))
                        .system(systemPrompt)
                        .call()
                        .content();
            }
        } catch (Exception e) {
            log.warn("Falha ao comunicar com Ollama LLM: {}. Aplicando raciocínio heurístico inteligente de fallback.", e.getMessage());
        }

        // Fallback Heurístico Robusto caso o servidor Ollama esteja indisponível
        return generateHeuristicAnalysis(serviceName, errorReason, recentLogs, podHealth);
    }

    private String generateHeuristicAnalysis(String serviceName, String errorReason, String logs, String podHealth) {
        if (logs.contains("HikariPool") || logs.contains("Connection refused") || logs.contains("PostgreSQL")) {
            return """
                [CAUSA_RAIZ]
                Falha de conexão com o banco de dados PostgreSQL. O pool de conexões (HikariCP) não conseguiu obter novas conexões ou houve esgotamento das conexões simultâneas configuradas.
                [PLANO_DE_ACAO]
                1. Verificar o status do pod do PostgreSQL (kubectl get pod postgres -n keepguard).
                2. Checar as credenciais e limites de conexões máximas no application.yml (spring.datasource.hikari.maximum-pool-size).
                3. Analisar se há queries lentas bloqueando as conexões ativas.
                """;
        }

        if (podHealth.contains("OOMKilled") || logs.contains("OutOfMemoryError") || podHealth.contains("Exit Code: 137")) {
            return """
                [CAUSA_RAIZ]
                O container foi encerrado pelo kernel do Kubernetes por estouro do limite de memória (OOMKilled - Exit Code 137). A JVM atingiu o teto de RAM alocado no Helm deployment.
                [PLANO_DE_ACAO]
                1. Aumentar o limite de memória no values.yaml do microsserviço (resources.limits.memory).
                2. Ajustar os parâmetros de heap da JVM (-XX:MaxRAMPercentage=75.0).
                3. Verificar possíveis vazamentos de memória ou payloads excessivos na requisição.
                """;
        }

        if (logs.contains("RedisConnectionException") || logs.contains("RedisURIs must not be empty")) {
            return """
                [CAUSA_RAIZ]
                Erro na inicialização do cliente Lettuce/Redis. O microsserviço tentou conectar a um nó de Redis inexistente ou com profile incompatível.
                [PLANO_DE_ACAO]
                1. Verificar as variáveis SPRING_DATA_REDIS_HOST e SPRING_DATA_REDIS_PORT no Helm.
                2. Garantir que a variável spring.data.redis.cluster.nodes não esteja populada incorretamente.
                """;
        }

        return """
            [CAUSA_RAIZ]
            Instabilidade ou reinicialização detectada no container durante a execução do processo.
            [PLANO_DE_ACAO]
            1. Inspecionar logs completos do container usando: kubectl logs %s -n keepguard --previous.
            2. Validar se as variáveis de ambiente e secrets compartilhados estão sincronizados.
            """.formatted(serviceName);
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

    private IncidentSeverity evaluateSeverity(String errorReason, String logs, String podHealth) {
        if (podHealth.contains("OOMKilled") || logs.contains("OutOfMemoryError") || "CrashLoopBackOff".equalsIgnoreCase(errorReason)) {
            return IncidentSeverity.CRITICAL;
        }
        if (logs.contains("Connection refused") || logs.contains("HikariPool")) {
            return IncidentSeverity.HIGH;
        }
        return IncidentSeverity.MEDIUM;
    }
}
