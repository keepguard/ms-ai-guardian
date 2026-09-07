package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PullRequestLifecycleRepositoryPort {
    PullRequestLifecycle save(PullRequestLifecycle lifecycle);

    Optional<PullRequestLifecycle> findByRepoNameAndPrNumber(String repoName, Integer prNumber);

    Optional<PullRequestLifecycle> findByRepoNameAndBranchName(String repoName, String branchName);

    List<PullRequestLifecycle> findByRepoNameAndIncidentIdAndStatusIn(
            String repoName, UUID incidentId, Collection<PullRequestStatus> statuses);

    List<PullRequestLifecycle> findByStatusIn(Collection<PullRequestStatus> statuses);
}
