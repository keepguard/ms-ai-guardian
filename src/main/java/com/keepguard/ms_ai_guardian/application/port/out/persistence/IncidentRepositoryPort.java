package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepositoryPort {
    Incident save(Incident incident);

    Optional<Incident> findById(UUID id);

    Page<Incident> search(Map<String, String> query, Pageable pageable);

    List<Incident> findByNamespaceOrderByCreatedAtDesc(String namespace);

    Optional<Incident> findFirstByFingerprintOrderByCreatedAtDesc(String fingerprint);

    Optional<Incident> findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(String podName, LocalDateTime after);

    Optional<Incident> findTopByServiceNameAndErrorReasonAndCreatedAtAfterOrderByCreatedAtDesc(
            String serviceName, String errorReason, LocalDateTime after);

    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);
}
