package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface IncidentActionSuggestionRepository extends JpaRepository<IncidentActionSuggestion, UUID> {
    List<IncidentActionSuggestion> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    @Modifying
    @Transactional
    void deleteByIncidentId(UUID incidentId);
}
