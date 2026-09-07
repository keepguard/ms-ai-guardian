package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentAlertDelivery;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentAlertDeliveryJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentAlertDeliveryJpaMapper {

    public IncidentAlertDelivery toDomain(IncidentAlertDeliveryJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return IncidentAlertDelivery.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .email(entity.getEmail())
                .outcome(entity.getOutcome())
                .kind(entity.getKind())
                .correlationId(entity.getCorrelationId())
                .sentAt(entity.getSentAt())
                .build();
    }

    public IncidentAlertDeliveryJpaEntity toEntity(IncidentAlertDelivery domain) {
        if (domain == null) {
            return null;
        }
        return IncidentAlertDeliveryJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .email(domain.getEmail())
                .outcome(domain.getOutcome())
                .kind(domain.getKind())
                .correlationId(domain.getCorrelationId())
                .sentAt(domain.getSentAt())
                .build();
    }
}
