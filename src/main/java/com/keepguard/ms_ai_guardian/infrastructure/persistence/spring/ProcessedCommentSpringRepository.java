package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.ProcessedCommentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedCommentSpringRepository extends JpaRepository<ProcessedCommentJpaEntity, UUID> {
    boolean existsByCommentId(String commentId);
}
