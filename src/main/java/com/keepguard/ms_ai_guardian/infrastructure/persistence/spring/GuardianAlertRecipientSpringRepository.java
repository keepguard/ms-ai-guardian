package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.GuardianAlertRecipientJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardianAlertRecipientSpringRepository extends JpaRepository<GuardianAlertRecipientJpaEntity, UUID> {
    List<GuardianAlertRecipientJpaEntity> findAllByOrderByCreatedAtAsc();

    List<GuardianAlertRecipientJpaEntity> findByEnabledTrueOrderByCreatedAtAsc();

    Optional<GuardianAlertRecipientJpaEntity> findByEmailIgnoreCase(String email);

    long countByEnabledTrue();
}
