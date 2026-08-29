package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuardianAlertRecipientRepository extends JpaRepository<GuardianAlertRecipient, UUID> {
    List<GuardianAlertRecipient> findAllByOrderByCreatedAtAsc();

    List<GuardianAlertRecipient> findByEnabledTrueOrderByCreatedAtAsc();

    Optional<GuardianAlertRecipient> findByEmailIgnoreCase(String email);

    long countByEnabledTrue();
}
