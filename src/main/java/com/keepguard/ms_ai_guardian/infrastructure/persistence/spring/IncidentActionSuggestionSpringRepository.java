package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentActionSuggestionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentActionSuggestionSpringRepository extends JpaRepository<IncidentActionSuggestionJpaEntity, UUID> {
    List<IncidentActionSuggestionJpaEntity> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    @Modifying
    @Transactional
    void deleteByIncidentId(UUID incidentId);
}
