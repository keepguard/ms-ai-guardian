package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentActionSuggestionRepositoryPort {
    IncidentActionSuggestion save(IncidentActionSuggestion suggestion);

    Optional<IncidentActionSuggestion> findById(UUID id);

    List<IncidentActionSuggestion> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    void deleteByIncidentId(UUID incidentId);
}
