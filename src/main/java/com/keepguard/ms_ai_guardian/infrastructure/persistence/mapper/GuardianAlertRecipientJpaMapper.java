package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.GuardianAlertRecipientJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class GuardianAlertRecipientJpaMapper {

    public GuardianAlertRecipient toDomain(GuardianAlertRecipientJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return GuardianAlertRecipient.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .enabled(entity.isEnabled())
                .label(entity.getLabel())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public GuardianAlertRecipientJpaEntity toEntity(GuardianAlertRecipient domain) {
        if (domain == null) {
            return null;
        }
        return GuardianAlertRecipientJpaEntity.builder()
                .id(domain.getId())
                .email(domain.getEmail())
                .enabled(domain.isEnabled())
                .label(domain.getLabel())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
