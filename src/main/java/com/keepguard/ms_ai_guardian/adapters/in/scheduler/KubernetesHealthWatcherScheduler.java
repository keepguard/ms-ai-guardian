package com.keepguard.ms_ai_guardian.adapters.in.scheduler;

import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.service.AiDiagnosticService;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KubernetesHealthWatcherScheduler {

    private final KubernetesInspectorService k8sInspector;
    private final AiDiagnosticService aiDiagnosticService;
    private final com.keepguard.ms_ai_guardian.application.service.agents.CoderAgentService coderAgentService;
    private final com.keepguard.ms_ai_guardian.application.service.agents.ReviewerAgentService reviewerAgentService;
    private final com.keepguard.ms_ai_guardian.application.service.agents.DeployerAgentService deployerAgentService;
    private final com.keepguard.ms_ai_guardian.adapters.out.github.GitHubApiClient gitHubApiClient;
    private final com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository prRepository;

    private final com.keepguard.ms_ai_guardian.domain.repository.ProcessedCommentRepository processedCommentRepository;

    @Value("${app.guardian.namespace:keepguard}")
    private String targetNamespace;

    @Value("${app.guardian.watcher-enabled:true}")
    private boolean watcherEnabled;

    // Executa a cada 45 segundos buscando novos comentários e merges humanos no GitHub de PRs estritamente ativos
    @Scheduled(fixedDelay = 45000, initialDelay = 10000)
    public void scanPullRequestInteractions() {
        try {
            var openPrs = prRepository.findAll().stream()
                    .filter(pr -> pr.getPrNumber() != null && 
                            ("OPEN".equals(pr.getStatus()) || "CHANGES_REQUESTED".equals(pr.getStatus()) || "AI_APPROVED".equals(pr.getStatus())))
                    .toList();

            for (var pr : openPrs) {
                String repo = pr.getRepoName();
                int num = pr.getPrNumber();

                // 1. Checa se o humano fez Merge no GitHub
                var statusMap = gitHubApiClient.getPullRequestStatus(repo, num);
                boolean isMerged = (boolean) statusMap.getOrDefault("merged", false);
                if (isMerged && !pr.isMergedByHuman()) {
                    String mergedBy = (String) statusMap.getOrDefault("mergedBy", "rafael-soares");
                    log.info("🎉 [Scheduler Watcher] Merge detectado no PR #{} de {} por @{}!", num, repo, mergedBy);
                    deployerAgentService.handleMergedPullRequest(repo, num, mergedBy);
                    continue;
                }

                // 2. Checa se há comentários inline de revisão novos do humano
                var comments = gitHubApiClient.getPrReviewComments(repo, num);
                for (var comment : comments) {
                    String body = comment.get("body");
                    String author = comment.get("author");
                    String commentId = comment.get("id");

                    // Processa apenas comentários que ainda NÃO foram registrados no banco
                    if (processedCommentRepository.existsByCommentId(commentId)) {
                        continue;
                    }

                    if (!author.contains("bot") && !body.contains("[CoderAgent]") && !body.contains("[ReviewerAgent]")) {
                        log.info("💬 [Scheduler Watcher] Processando comentário #{} de @{}: {}", commentId, author, body);
                        
                        processedCommentRepository.save(com.keepguard.ms_ai_guardian.domain.entity.ProcessedComment.builder()
                                .commentId(commentId)
                                .prNumber(num)
                                .build());

                        boolean adjusted = coderAgentService.applyReviewFeedbackAndNotify(repo, num, commentId, body, author);
                        if (adjusted) {
                            prRepository.findByRepoNameAndPrNumber(repo, num)
                                    .ifPresent(reviewerAgentService::performReview);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao verificar interações do GitHub: {}", e.getMessage());
        }
    }

    // Executa a cada 60 segundos buscando anomalias no cluster
    @Scheduled(fixedDelayString = "${app.guardian.scan-interval-ms:60000}", initialDelay = 15000)
    public void scanClusterHealth() {
        if (!watcherEnabled) {
            return;
        }

        try {
            // 1. Inspeciona Pods com anomalias/restarts
            List<Pod> unhealthyPods = k8sInspector.listUnhealthyPods(targetNamespace);
            log.info("🔍 [AI Guardian Watcher] Varredura do cluster (namespace: {}). Pods anômalos detectados: {}", targetNamespace, unhealthyPods.size());
            for (Pod pod : unhealthyPods) {
                String podName = pod.getMetadata().getName();
                String serviceName = pod.getMetadata().getLabels() != null && pod.getMetadata().getLabels().containsKey("app")
                        ? pod.getMetadata().getLabels().get("app")
                        : podName;

                log.info("🚨 [AI Guardian Watcher] Processando pod anômalo: {} (Serviço: {})", podName, serviceName);

                String errorReason = "RESTART_OR_FAILURE_DETECTED";
                if (pod.getStatus().getPhase() != null) {
                    errorReason = pod.getStatus().getPhase();
                }

                // Se o pod estiver Running com anomalia de log, extrai o erro específico
                String logs = k8sInspector.getPodLogs(targetNamespace, podName, 20);
                if (logs.contains("CODE_DEFECT_")) {
                    int idx = logs.indexOf("CODE_DEFECT_");
                    int end = logs.indexOf("\n", idx);
                    errorReason = end != -1 ? logs.substring(idx, end).trim() : logs.substring(idx, Math.min(idx + 50, logs.length()));
                } else if (logs.contains("PANIC RECOVER") || logs.contains("PANIC_RUNTIME")) {
                    errorReason = "PANIC_RUNTIME_EXCEPTION";
                } else if (logs.contains("NullPointerException")) {
                    errorReason = "NullPointerException";
                }

                log.info("🔍 [AI Guardian Watcher] Causa identificada para {}: {}", podName, errorReason);

                // Se já existir um PR aberto/pendente para este microsserviço, não dispara novo diagnóstico nem e-mails
                boolean hasActivePr = prRepository.findAll().stream()
                        .anyMatch(pr -> serviceName.equalsIgnoreCase(pr.getRepoName()) && 
                                ("OPEN".equals(pr.getStatus()) || "AI_APPROVED".equals(pr.getStatus()) || "CHANGES_REQUESTED".equals(pr.getStatus())));

                if (hasActivePr) {
                    log.info("⏳ [AI Guardian] Microsserviço {} já possui um PR de hotfix ativo em andamento. Notificação suprimida.", serviceName);
                    continue;
                }

                aiDiagnosticService.diagnosePod(targetNamespace, podName, serviceName, errorReason, false);
            }

            // 2. Inspeciona Deployments zerados (0 pods disponíveis / indisponibilidade total)
            List<io.fabric8.kubernetes.api.model.apps.Deployment> zeroDeployments = k8sInspector.listDeploymentsWithZeroReplicas(targetNamespace);
            for (var dep : zeroDeployments) {
                String depName = dep.getMetadata().getName();
                String errorReason = "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE";
                aiDiagnosticService.diagnosePod(targetNamespace, depName + "-deployment", depName, errorReason, false);
            }

            if (unhealthyPods.isEmpty() && zeroDeployments.isEmpty()) {
                log.debug("🛡️ [AI Guardian] Cluster {} saudável. Nenhum pod em estado anômalo.", targetNamespace);
            }

        } catch (Exception e) {
            log.error("Erro durante a varredura do cluster pelo AI Guardian: {}", e.getMessage(), e);
        }
    }
}
