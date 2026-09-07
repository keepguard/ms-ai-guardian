package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;

import java.util.List;
import java.util.UUID;

public interface IncidentEvidenceRepositoryPort {
    IncidentEvidence save(IncidentEvidence evidence);

    List<IncidentEvidence> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
