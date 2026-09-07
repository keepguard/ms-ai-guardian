package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentActionExecutionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentActionExecutionSpringRepository extends JpaRepository<IncidentActionExecutionJpaEntity, UUID> {
    List<IncidentActionExecutionJpaEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
