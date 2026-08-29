package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentAlertDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentAlertDeliveryRepository extends JpaRepository<IncidentAlertDelivery, UUID> {
    List<IncidentAlertDelivery> findByIncidentIdOrderBySentAtDesc(UUID incidentId);
}
