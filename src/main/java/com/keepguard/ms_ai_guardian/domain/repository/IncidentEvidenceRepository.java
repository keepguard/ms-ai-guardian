package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentEvidenceRepository extends JpaRepository<IncidentEvidence, UUID> {
    List<IncidentEvidence> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
