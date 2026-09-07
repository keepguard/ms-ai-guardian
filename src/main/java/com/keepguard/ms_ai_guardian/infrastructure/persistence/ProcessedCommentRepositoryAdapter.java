package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.ProcessedCommentRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.ProcessedComment;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.ProcessedCommentJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.ProcessedCommentSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProcessedCommentRepositoryAdapter implements ProcessedCommentRepositoryPort {

    private final ProcessedCommentSpringRepository springRepository;
    private final ProcessedCommentJpaMapper mapper;

    @Override
    public ProcessedComment save(ProcessedComment domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public boolean existsByCommentId(String commentId) {
        return springRepository.existsByCommentId(commentId);
    }
}
