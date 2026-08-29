package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentActionExecutionRepository extends JpaRepository<IncidentActionExecution, UUID> {
    List<IncidentActionExecution> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
