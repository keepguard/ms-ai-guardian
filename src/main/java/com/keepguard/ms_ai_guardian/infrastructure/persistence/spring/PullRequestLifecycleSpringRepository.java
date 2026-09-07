package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.PullRequestLifecycleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PullRequestLifecycleSpringRepository extends JpaRepository<PullRequestLifecycleJpaEntity, UUID> {
    Optional<PullRequestLifecycleJpaEntity> findByRepoNameAndPrNumber(String repoName, Integer prNumber);

    Optional<PullRequestLifecycleJpaEntity> findByRepoNameAndBranchName(String repoName, String branchName);

    List<PullRequestLifecycleJpaEntity> findByRepoNameAndIncidentIdAndStatusIn(
            String repoName, UUID incidentId, Collection<PullRequestStatus> statuses);

    List<PullRequestLifecycleJpaEntity> findByStatusIn(Collection<PullRequestStatus> statuses);
}
