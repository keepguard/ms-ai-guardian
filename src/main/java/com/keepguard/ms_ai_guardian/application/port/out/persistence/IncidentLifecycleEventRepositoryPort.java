package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentLifecycleEvent;

import java.util.List;
import java.util.UUID;

public interface IncidentLifecycleEventRepositoryPort {
    IncidentLifecycleEvent save(IncidentLifecycleEvent event);

    List<IncidentLifecycleEvent> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);
}
