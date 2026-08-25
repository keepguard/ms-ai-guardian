package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.ProcessedComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedCommentRepository extends JpaRepository<ProcessedComment, UUID> {
    boolean existsByCommentId(String commentId);
}
