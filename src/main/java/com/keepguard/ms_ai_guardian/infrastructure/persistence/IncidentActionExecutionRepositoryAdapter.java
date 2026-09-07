package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentActionExecutionRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionExecution;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.IncidentActionExecutionJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.IncidentActionExecutionSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IncidentActionExecutionRepositoryAdapter implements IncidentActionExecutionRepositoryPort {

    private final IncidentActionExecutionSpringRepository springRepository;
    private final IncidentActionExecutionJpaMapper mapper;

    @Override
    public IncidentActionExecution save(IncidentActionExecution domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public List<IncidentActionExecution> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId) {
        return springRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
