package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentAlertDeliveryRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentAlertDelivery;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.IncidentAlertDeliveryJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.IncidentAlertDeliverySpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IncidentAlertDeliveryRepositoryAdapter implements IncidentAlertDeliveryRepositoryPort {

    private final IncidentAlertDeliverySpringRepository springRepository;
    private final IncidentAlertDeliveryJpaMapper mapper;

    @Override
    public IncidentAlertDelivery save(IncidentAlertDelivery domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public List<IncidentAlertDelivery> findByIncidentIdOrderBySentAtDesc(UUID incidentId) {
        return springRepository.findByIncidentIdOrderBySentAtDesc(incidentId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
