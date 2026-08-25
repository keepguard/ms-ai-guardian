package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findByNamespaceOrderByCreatedAtDesc(String namespace);

    Optional<Incident> findFirstByFingerprintOrderByCreatedAtDesc(String fingerprint);

    // Para evitar tempestade de alertas: verifica se já houve um incidente recente para o mesmo pod/serviço
    Optional<Incident> findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(String podName, LocalDateTime after);

    Optional<Incident> findTopByServiceNameAndErrorReasonAndCreatedAtAfterOrderByCreatedAtDesc(
            String serviceName, String errorReason, LocalDateTime after);
}
