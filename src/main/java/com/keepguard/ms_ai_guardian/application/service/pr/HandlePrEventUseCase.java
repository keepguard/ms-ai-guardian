package com.keepguard.ms_ai_guardian.application.service.pr;

import com.keepguard.ms_ai_guardian.application.port.in.HandlePrEventPort;
import com.keepguard.ms_ai_guardian.application.port.out.cache.IdempotencyPort;
import com.keepguard.ms_ai_guardian.application.port.out.github.GitHubPort;
import com.keepguard.ms_ai_guardian.application.service.agents.CoderAgentService;
import com.keepguard.ms_ai_guardian.application.service.agents.DeployerAgentService;
import com.keepguard.ms_ai_guardian.application.service.agents.ReviewerAgentService;
import com.keepguard.ms_ai_guardian.domain.entity.ProcessedComment;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import com.keepguard.ms_ai_guardian.domain.repository.ProcessedCommentRepository;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HandlePrEventUseCase implements HandlePrEventPort {

    private final CoderAgentService coderAgent;
    private final ReviewerAgentService reviewerAgent;
    private final DeployerAgentService deployerAgent;
    private final PullRequestLifecycleRepository prRepository;
    private final ProcessedCommentRepository processedCommentRepository;
    private final GitHubPort gitHubClient;
    private final IdempotencyPort idempotency;
    private final GuardianProperties properties;

    @Override
    public void onPullRequest(String repoName, int prNumber, String action, boolean merged, String sender) {
        if ("closed".equalsIgnoreCase(action) && merged) {
            log.info("Quality gate humano aprovado. Merge por @{}. Acionando DeployerAgent.", sender);
            deployerAgent.handleMergedPullRequest(repoName, prNumber, sender);
            return;
        }
        if ("opened".equalsIgnoreCase(action) || "reopened".equalsIgnoreCase(action)) {
            prRepository.findByRepoNameAndPrNumber(repoName, prNumber)
                    .ifPresent(reviewerAgent::performReview);
        }
    }

    @Override
    public void onComment(String repoName, int prNumber, String commentId, String body, String author) {
        if (isBotComment(author, body)) {
            return;
        }
        if (commentId != null && processedCommentRepository.existsByCommentId(commentId)) {
            return;
        }
        if (commentId != null) {
            processedCommentRepository.save(ProcessedComment.builder()
                    .commentId(commentId)
                    .prNumber(prNumber)
                    .build());
        }
        boolean adjusted = coderAgent.applyReviewFeedbackAndNotify(repoName, prNumber, commentId, body, author);
        if (adjusted) {
            prRepository.findByRepoNameAndPrNumber(repoName, prNumber)
                    .ifPresent(reviewerAgent::performReview);
        }
    }

    @Override
    public void scanOpenPullRequests() {
        var openPrs = prRepository.findByStatusIn(PullRequestStatus.active());
        for (var pr : openPrs) {
            if (pr.getPrNumber() == null) {
                continue;
            }
            String repo = pr.getRepoName();
            int num = pr.getPrNumber();
            var statusMap = gitHubClient.getPullRequestStatus(repo, num);
            boolean isMerged = (boolean) statusMap.getOrDefault("merged", false);
            if (isMerged && !pr.isMergedByHuman()) {
                String mergedBy = (String) statusMap.getOrDefault("mergedBy", properties.getApproverGithub());
                deployerAgent.handleMergedPullRequest(repo, num, mergedBy);
                continue;
            }
            for (var comment : gitHubClient.getPrReviewComments(repo, num)) {
                onComment(repo, num, comment.get("id"), comment.get("body"), comment.get("author"));
            }
        }
    }

    public boolean beginDelivery(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return true;
        }
        return idempotency.tryBegin("gh:" + deliveryId, properties.getRedis().getIdempotencyTtlSeconds());
    }

    static boolean isBotComment(String author, String body) {
        String a = author != null ? author : "";
        String b = body != null ? body : "";
        return a.contains("bot") || b.contains("[CoderAgent]") || b.contains("[ReviewerAgent]");
    }
}
