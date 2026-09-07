package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.ProcessedComment;

public interface ProcessedCommentRepositoryPort {
    ProcessedComment save(ProcessedComment comment);

    boolean existsByCommentId(String commentId);
}
