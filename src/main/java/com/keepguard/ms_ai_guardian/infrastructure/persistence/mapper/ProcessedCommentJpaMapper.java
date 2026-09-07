package com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper;

import com.keepguard.ms_ai_guardian.domain.entity.ProcessedComment;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.ProcessedCommentJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class ProcessedCommentJpaMapper {

    public ProcessedComment toDomain(ProcessedCommentJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return ProcessedComment.builder()
                .id(entity.getId())
                .commentId(entity.getCommentId())
                .prNumber(entity.getPrNumber())
                .processedAt(entity.getProcessedAt())
                .build();
    }

    public ProcessedCommentJpaEntity toEntity(ProcessedComment domain) {
        if (domain == null) {
            return null;
        }
        return ProcessedCommentJpaEntity.builder()
                .id(domain.getId())
                .commentId(domain.getCommentId())
                .prNumber(domain.getPrNumber())
                .processedAt(domain.getProcessedAt())
                .build();
    }
}
