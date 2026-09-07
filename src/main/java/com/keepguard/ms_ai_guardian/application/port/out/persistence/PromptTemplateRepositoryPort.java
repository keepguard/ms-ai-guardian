package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.PromptTemplate;

import java.util.Optional;

public interface PromptTemplateRepositoryPort {
    PromptTemplate save(PromptTemplate template);

    Optional<PromptTemplate> findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(String promptKey, String status);

    boolean existsByPromptKeyAndStatus(String promptKey, String status);
}
