package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuardianAlertRecipientRepositoryPort {
    GuardianAlertRecipient save(GuardianAlertRecipient recipient);

    Optional<GuardianAlertRecipient> findById(UUID id);

    List<GuardianAlertRecipient> findAllByOrderByCreatedAtAsc();

    List<GuardianAlertRecipient> findByEnabledTrueOrderByCreatedAtAsc();

    Optional<GuardianAlertRecipient> findByEmailIgnoreCase(String email);

    long countByEnabledTrue();

    long count();
}
