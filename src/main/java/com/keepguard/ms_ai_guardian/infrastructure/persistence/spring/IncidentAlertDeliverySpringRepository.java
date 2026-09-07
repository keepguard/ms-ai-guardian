package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentAlertDeliveryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentAlertDeliverySpringRepository extends JpaRepository<IncidentAlertDeliveryJpaEntity, UUID> {
    List<IncidentAlertDeliveryJpaEntity> findByIncidentIdOrderBySentAtDesc(UUID incidentId);
}
