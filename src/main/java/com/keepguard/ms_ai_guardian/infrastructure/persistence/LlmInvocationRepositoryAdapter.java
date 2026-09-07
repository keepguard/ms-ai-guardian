package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.LlmInvocationRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.LlmInvocationJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.LlmInvocationSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LlmInvocationRepositoryAdapter implements LlmInvocationRepositoryPort {

    private final LlmInvocationSpringRepository springRepository;
    private final LlmInvocationJpaMapper mapper;

    @Override
    public LlmInvocation save(LlmInvocation domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

}
