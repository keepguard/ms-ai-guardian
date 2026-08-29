package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PullRequestLifecycleRepository extends JpaRepository<PullRequestLifecycle, UUID> {
    Optional<PullRequestLifecycle> findByRepoNameAndPrNumber(String repoName, Integer prNumber);
    Optional<PullRequestLifecycle> findByRepoNameAndBranchName(String repoName, String branchName);

    List<PullRequestLifecycle> findByRepoNameAndIncidentIdAndStatusIn(
            String repoName, UUID incidentId, Collection<PullRequestStatus> statuses);

    List<PullRequestLifecycle> findByStatusIn(Collection<PullRequestStatus> statuses);
}
