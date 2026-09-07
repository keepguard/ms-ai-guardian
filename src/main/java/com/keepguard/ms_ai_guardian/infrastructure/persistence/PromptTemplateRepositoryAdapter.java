package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.PromptTemplateRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.PromptTemplate;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.PromptTemplateJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.PromptTemplateSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PromptTemplateRepositoryAdapter implements PromptTemplateRepositoryPort {

    private final PromptTemplateSpringRepository springRepository;
    private final PromptTemplateJpaMapper mapper;

    @Override
    public PromptTemplate save(PromptTemplate domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public Optional<PromptTemplate> findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(String promptKey, String status) {
        return springRepository.findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(promptKey, status).map(mapper::toDomain);
    }

    @Override
    public boolean existsByPromptKeyAndStatus(String promptKey, String status) {
        return springRepository.existsByPromptKeyAndStatus(promptKey, status);
    }
}
