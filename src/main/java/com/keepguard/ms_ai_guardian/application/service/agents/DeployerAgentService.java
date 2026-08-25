package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeployerAgentService {

    private final PullRequestLifecycleRepository prRepository;
    private final EmailNotificationService emailNotificationService;
    private final KubernetesClient k8sClient;
    private final com.keepguard.ms_ai_guardian.infrastructure.lock.DistributedDeployLockService deployLockService;

    @Value("${app.guardian.namespace:keepguard}")
    private String namespace;

    /**
     * Executado quando o Humano (Rafael) realiza o Merge no GitHub.
     */
    @Transactional
    public boolean handleMergedPullRequest(String repoName, int prNumber, String mergedBy) {
        log.info("🚀 [DeployerAgent] Merge detectado no PR #{} do repositório {} efetuado por @{}. Iniciando esteira de deploy...",
                prNumber, repoName, mergedBy);

        var prOpt = prRepository.findByRepoNameAndPrNumber(repoName, prNumber);
        PullRequestLifecycle pr = prOpt.orElseGet(() -> PullRequestLifecycle.builder()
                .repoName(repoName)
                .prNumber(prNumber)
                .branchName("main")
                .baseBranch("main")
                .status("MERGED_BY_HUMAN")
                .build());

        pr.setMergedByHuman(true);
        pr.setStatus("MERGED_BY_HUMAN");
        prRepository.save(pr);

        // 🔒 DISTRIBUTED DEPLOY LOCK: Impede que outro deploy simultâneo do mesmo serviço atropele o pod
        String deployId = "deploy_pr_" + prNumber + "_" + System.currentTimeMillis();
        boolean lockAcquired = deployLockService.tryAcquireDeployLock(repoName, deployId);
        if (!lockAcquired) {
            log.warn("⏳ [DeployerAgent] Deploy do serviço {} aguardará na fila pois outro rollout está em andamento.", repoName);
            return false;
        }

        try {
            // 1. Notifica por e-mail que o DeployerAgent assumiu o deploy e iniciou o rollout
            emailNotificationService.sendDeployStartedEmail(pr, mergedBy);

            // 2. Executa o Rollout Restart no Kubernetes para o Deployment correspondente
            log.info("🔄 [DeployerAgent] Aplicando rollout restart no deployment '{}' no namespace '{}'...", repoName, namespace);
            k8sClient.apps().deployments().inNamespace(namespace).withName(repoName).rolling().restart();

            pr.setDeployedToK8s(true);
            pr.setStatus("DEPLOYED");
            prRepository.save(pr);

            log.info("🎉 [DeployerAgent] Deploy do hotfix concluído com sucesso para o serviço: {}", repoName);

            // 3. Envia e-mail de celebração e conclusão do ciclo
            emailNotificationService.sendDeployCompletedEmail(pr, mergedBy);
            return true;

        } catch (Exception e) {
            log.error("Erro no [DeployerAgent] ao realizar rollout no K8s para {}: {}", repoName, e.getMessage(), e);
            return false;
        } finally {
            deployLockService.releaseDeployLock(repoName);
        }
    }
}
