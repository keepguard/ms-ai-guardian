package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentLifecycleEventRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentLifecycleEvent;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.IncidentLifecycleEventJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.IncidentLifecycleEventSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IncidentLifecycleEventRepositoryAdapter implements IncidentLifecycleEventRepositoryPort {

    private final IncidentLifecycleEventSpringRepository springRepository;
    private final IncidentLifecycleEventJpaMapper mapper;

    @Override
    public IncidentLifecycleEvent save(IncidentLifecycleEvent domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public List<IncidentLifecycleEvent> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId) {
        return springRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
