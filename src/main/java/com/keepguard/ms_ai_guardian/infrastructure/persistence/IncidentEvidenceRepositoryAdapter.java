package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentEvidenceRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.IncidentEvidenceJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.IncidentEvidenceSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IncidentEvidenceRepositoryAdapter implements IncidentEvidenceRepositoryPort {

    private final IncidentEvidenceSpringRepository springRepository;
    private final IncidentEvidenceJpaMapper mapper;

    @Override
    public IncidentEvidence save(IncidentEvidence domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public List<IncidentEvidence> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId) {
        return springRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
