package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;

import java.util.List;
import java.util.UUID;

public interface IncidentActionExecutionRepositoryPort {
    IncidentActionExecution save(IncidentActionExecution execution);

    List<IncidentActionExecution> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
