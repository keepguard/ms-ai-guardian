package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentActionSuggestionRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.IncidentActionSuggestionJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.IncidentActionSuggestionSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IncidentActionSuggestionRepositoryAdapter implements IncidentActionSuggestionRepositoryPort {

    private final IncidentActionSuggestionSpringRepository springRepository;
    private final IncidentActionSuggestionJpaMapper mapper;

    @Override
    public IncidentActionSuggestion save(IncidentActionSuggestion domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public Optional<IncidentActionSuggestion> findById(UUID id) {
        return springRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<IncidentActionSuggestion> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId) {
        return springRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByIncidentId(UUID incidentId) {
        springRepository.deleteByIncidentId(incidentId);
    }
}
