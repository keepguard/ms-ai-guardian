package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentSpringRepository extends JpaRepository<IncidentJpaEntity, UUID>, JpaSpecificationExecutor<IncidentJpaEntity> {

    List<IncidentJpaEntity> findByNamespaceOrderByCreatedAtDesc(String namespace);

    Optional<IncidentJpaEntity> findFirstByFingerprintOrderByCreatedAtDesc(String fingerprint);

    Optional<IncidentJpaEntity> findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(String podName, LocalDateTime after);

    Optional<IncidentJpaEntity> findTopByServiceNameAndErrorReasonAndCreatedAtAfterOrderByCreatedAtDesc(
            String serviceName, String errorReason, LocalDateTime after);

    List<IncidentJpaEntity> findByStatusIn(Collection<IncidentStatus> statuses);
}
