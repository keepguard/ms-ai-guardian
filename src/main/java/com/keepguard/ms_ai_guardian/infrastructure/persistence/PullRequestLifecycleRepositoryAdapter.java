package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.PullRequestLifecycleRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.PullRequestLifecycleJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.PullRequestLifecycleSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PullRequestLifecycleRepositoryAdapter implements PullRequestLifecycleRepositoryPort {

    private final PullRequestLifecycleSpringRepository springRepository;
    private final PullRequestLifecycleJpaMapper mapper;

    @Override
    public PullRequestLifecycle save(PullRequestLifecycle domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public Optional<PullRequestLifecycle> findByRepoNameAndPrNumber(String repoName, Integer prNumber) {
        return springRepository.findByRepoNameAndPrNumber(repoName, prNumber).map(mapper::toDomain);
    }

    @Override
    public Optional<PullRequestLifecycle> findByRepoNameAndBranchName(String repoName, String branchName) {
        return springRepository.findByRepoNameAndBranchName(repoName, branchName).map(mapper::toDomain);
    }

    @Override
    public List<PullRequestLifecycle> findByRepoNameAndIncidentIdAndStatusIn(
            String repoName, UUID incidentId, Collection<PullRequestStatus> statuses) {
        return springRepository.findByRepoNameAndIncidentIdAndStatusIn(repoName, incidentId, statuses).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<PullRequestLifecycle> findByStatusIn(Collection<PullRequestStatus> statuses) {
        return springRepository.findByStatusIn(statuses).stream().map(mapper::toDomain).toList();
    }
}
