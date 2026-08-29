package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    Optional<PromptTemplate> findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(String promptKey, String status);

    boolean existsByPromptKeyAndStatus(String promptKey, String status);
}
