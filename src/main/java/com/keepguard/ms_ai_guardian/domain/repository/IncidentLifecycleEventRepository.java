package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentLifecycleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentLifecycleEventRepository extends JpaRepository<IncidentLifecycleEvent, UUID> {
    List<IncidentLifecycleEvent> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);
}
