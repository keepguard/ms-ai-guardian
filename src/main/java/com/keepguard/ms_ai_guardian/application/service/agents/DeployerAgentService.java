package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.application.port.out.cache.DistributedLockPort;
import com.keepguard.ms_ai_guardian.application.port.out.k8s.KubernetesOpsPort;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeployerAgentService {

    private final PullRequestLifecycleRepository prRepository;
    private final EmailNotificationService emailNotificationService;
    private final KubernetesOpsPort kubernetesOps;
    private final DistributedLockPort deployLock;
    private final GuardianProperties properties;

    public boolean handleMergedPullRequest(String repoName, int prNumber, String mergedBy) {
        log.info("[DeployerAgent] Merge do PR #{} em {} por @{}", prNumber, repoName, mergedBy);

        var prOpt = prRepository.findByRepoNameAndPrNumber(repoName, prNumber);
        PullRequestLifecycle pr = prOpt.orElseGet(() -> PullRequestLifecycle.builder()
                .repoName(repoName)
                .prNumber(prNumber)
                .branchName("main")
                .baseBranch("main")
                .status(PullRequestStatus.MERGED_BY_HUMAN)
                .build());

        pr.setMergedByHuman(true);
        pr.setStatus(PullRequestStatus.MERGED_BY_HUMAN);
        prRepository.save(pr);

        String deployId = "deploy_pr_" + prNumber + "_" + System.currentTimeMillis();
        int ttl = properties.getRedis().getLockTtlSeconds();
        if (!deployLock.tryAcquire("deploy:" + repoName, deployId, ttl)) {
            log.warn("[DeployerAgent] Deploy de {} aguardará — outro rollout em andamento.", repoName);
            return false;
        }

        try {
            emailNotificationService.sendDeployStartedEmail(pr, mergedBy);
            kubernetesOps.rolloutRestart(properties.getNamespace(), repoName);
            pr.setDeployedToK8s(true);
            pr.setStatus(PullRequestStatus.DEPLOYED);
            prRepository.save(pr);
            emailNotificationService.sendDeployCompletedEmail(pr, mergedBy);
            return true;
        } catch (Exception e) {
            log.error("Erro no [DeployerAgent] ao realizar rollout no K8s para {}: {}", repoName, e.getMessage(), e);
            return false;
        } finally {
            deployLock.release("deploy:" + repoName, deployId);
        }
    }
}
