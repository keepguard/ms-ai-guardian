package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.GuardianAlertRecipientRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.GuardianAlertRecipientJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.GuardianAlertRecipientSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class GuardianAlertRecipientRepositoryAdapter implements GuardianAlertRecipientRepositoryPort {

    private final GuardianAlertRecipientSpringRepository springRepository;
    private final GuardianAlertRecipientJpaMapper mapper;

    @Override
    public GuardianAlertRecipient save(GuardianAlertRecipient domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public Optional<GuardianAlertRecipient> findById(UUID id) {
        return springRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<GuardianAlertRecipient> findAllByOrderByCreatedAtAsc() {
        return springRepository.findAllByOrderByCreatedAtAsc().stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<GuardianAlertRecipient> findByEnabledTrueOrderByCreatedAtAsc() {
        return springRepository.findByEnabledTrueOrderByCreatedAtAsc().stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<GuardianAlertRecipient> findByEmailIgnoreCase(String email) {
        return springRepository.findByEmailIgnoreCase(email).map(mapper::toDomain);
    }

    @Override
    public long countByEnabledTrue() {
        return springRepository.countByEnabledTrue();
    }

    @Override
    public long count() {
        return springRepository.count();
    }
}
