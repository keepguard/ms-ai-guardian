package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentAlertDelivery;

import java.util.List;
import java.util.UUID;

public interface IncidentAlertDeliveryRepositoryPort {
    IncidentAlertDelivery save(IncidentAlertDelivery delivery);

    List<IncidentAlertDelivery> findByIncidentIdOrderBySentAtDesc(UUID incidentId);
}
