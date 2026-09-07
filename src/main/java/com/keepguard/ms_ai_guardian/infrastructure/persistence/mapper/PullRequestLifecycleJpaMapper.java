package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.PullRequestLifecycleJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class PullRequestLifecycleJpaMapper {

    public PullRequestLifecycle toDomain(PullRequestLifecycleJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return PullRequestLifecycle.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .repoName(entity.getRepoName())
                .branchName(entity.getBranchName())
                .baseBranch(entity.getBaseBranch())
                .prNumber(entity.getPrNumber())
                .prUrl(entity.getPrUrl())
                .filePath(entity.getFilePath())
                .aiReviewed(entity.isAiReviewed())
                .aiApproved(entity.isAiApproved())
                .aiReviewFeedback(entity.getAiReviewFeedback())
                .humanApproved(entity.isHumanApproved())
                .mergedByHuman(entity.isMergedByHuman())
                .deployedToK8s(entity.isDeployedToK8s())
                .lastProcessedCommentId(entity.getLastProcessedCommentId())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PullRequestLifecycleJpaEntity toEntity(PullRequestLifecycle domain) {
        if (domain == null) {
            return null;
        }
        return PullRequestLifecycleJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .repoName(domain.getRepoName())
                .branchName(domain.getBranchName())
                .baseBranch(domain.getBaseBranch())
                .prNumber(domain.getPrNumber())
                .prUrl(domain.getPrUrl())
                .filePath(domain.getFilePath())
                .aiReviewed(domain.isAiReviewed())
                .aiApproved(domain.isAiApproved())
                .aiReviewFeedback(domain.getAiReviewFeedback())
                .humanApproved(domain.isHumanApproved())
                .mergedByHuman(domain.isMergedByHuman())
                .deployedToK8s(domain.isDeployedToK8s())
                .lastProcessedCommentId(domain.getLastProcessedCommentId())
                .status(domain.getStatus())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
