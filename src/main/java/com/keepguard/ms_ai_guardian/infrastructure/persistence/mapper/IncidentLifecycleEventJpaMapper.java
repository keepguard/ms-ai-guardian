package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentLifecycleEvent;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentLifecycleEventJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentLifecycleEventJpaMapper {

    public IncidentLifecycleEvent toDomain(IncidentLifecycleEventJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return IncidentLifecycleEvent.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .eventType(entity.getEventType())
                .detail(entity.getDetail())
                .correlationId(entity.getCorrelationId())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public IncidentLifecycleEventJpaEntity toEntity(IncidentLifecycleEvent domain) {
        if (domain == null) {
            return null;
        }
        return IncidentLifecycleEventJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .eventType(domain.getEventType())
                .detail(domain.getDetail())
                .correlationId(domain.getCorrelationId())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
