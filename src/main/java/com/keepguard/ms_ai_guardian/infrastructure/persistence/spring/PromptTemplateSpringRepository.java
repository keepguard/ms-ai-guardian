package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.PromptTemplateJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromptTemplateSpringRepository extends JpaRepository<PromptTemplateJpaEntity, UUID> {

    Optional<PromptTemplateJpaEntity> findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(String promptKey, String status);

    boolean existsByPromptKeyAndStatus(String promptKey, String status);
}
