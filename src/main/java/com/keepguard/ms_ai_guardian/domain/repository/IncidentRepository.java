package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    List<Incident> findByNamespaceOrderByCreatedAtDesc(String namespace);

    Optional<Incident> findFirstByFingerprintOrderByCreatedAtDesc(String fingerprint);

    Optional<Incident> findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(String podName, LocalDateTime after);

    Optional<Incident> findTopByServiceNameAndErrorReasonAndCreatedAtAfterOrderByCreatedAtDesc(
            String serviceName, String errorReason, LocalDateTime after);

    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);
}
