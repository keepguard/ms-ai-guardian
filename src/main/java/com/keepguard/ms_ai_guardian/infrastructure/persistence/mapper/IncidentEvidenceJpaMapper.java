package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentEvidenceJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentEvidenceJpaMapper {

    public IncidentEvidence toDomain(IncidentEvidenceJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return IncidentEvidence.builder()
                .id(entity.getId())
                .incidentId(entity.getIncidentId())
                .kind(entity.getKind())
                .payloadJson(entity.getPayloadJson())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public IncidentEvidenceJpaEntity toEntity(IncidentEvidence domain) {
        if (domain == null) {
            return null;
        }
        return IncidentEvidenceJpaEntity.builder()
                .id(domain.getId())
                .incidentId(domain.getIncidentId())
                .kind(domain.getKind())
                .payloadJson(domain.getPayloadJson())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
