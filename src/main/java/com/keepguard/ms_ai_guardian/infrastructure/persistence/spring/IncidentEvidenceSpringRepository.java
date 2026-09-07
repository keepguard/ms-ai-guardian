package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentEvidenceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentEvidenceSpringRepository extends JpaRepository<IncidentEvidenceJpaEntity, UUID> {
    List<IncidentEvidenceJpaEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
