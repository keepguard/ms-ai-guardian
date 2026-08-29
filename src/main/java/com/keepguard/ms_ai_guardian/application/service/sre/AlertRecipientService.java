package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import com.keepguard.ms_ai_guardian.domain.repository.GuardianAlertRecipientRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AlertRecipientService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final GuardianAlertRecipientRepository repository;
    private final GuardianProperties properties;

    @Transactional
    public List<GuardianAlertRecipient> listEnabledOrSeed() {
        List<GuardianAlertRecipient> enabled = repository.findByEnabledTrueOrderByCreatedAtAsc();
        if (!enabled.isEmpty()) {
            return enabled;
        }
        if (repository.count() == 0 && hasDefault()) {
            repository.save(GuardianAlertRecipient.builder()
                    .email(normalize(properties.getDefaultRecipient()))
                    .enabled(true)
                    .label("default")
                    .build());
            return repository.findByEnabledTrueOrderByCreatedAtAsc();
        }
        if (hasDefault()) {
            return List.of(GuardianAlertRecipient.builder()
                    .email(normalize(properties.getDefaultRecipient()))
                    .enabled(true)
                    .label("fallback")
                    .build());
        }
        return List.of();
    }

    public List<GuardianAlertRecipient> listAll() {
        listEnabledOrSeed();
        return repository.findAllByOrderByCreatedAtAsc();
    }

    @Transactional
    public GuardianAlertRecipient upsert(String email, String label, boolean enabled) {
        String normalized = normalize(email);
        if (!EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("E-mail inválido");
        }
        var existing = repository.findByEmailIgnoreCase(normalized);
        if (existing.isPresent()) {
            GuardianAlertRecipient rec = existing.get();
            rec.setEnabled(enabled);
            if (label != null) {
                rec.setLabel(label);
            }
            return repository.save(rec);
        }
        if (enabled && repository.countByEnabledTrue() >= properties.getMaxAlertRecipients()) {
            throw new IllegalArgumentException("Limite de destinatários ativos atingido");
        }
        return repository.save(GuardianAlertRecipient.builder()
                .email(normalized)
                .label(label)
                .enabled(enabled)
                .build());
    }

    @Transactional
    public GuardianAlertRecipient setEnabled(java.util.UUID id, boolean enabled) {
        GuardianAlertRecipient rec = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Destinatário não encontrado"));
        if (enabled && !rec.isEnabled() && repository.countByEnabledTrue() >= properties.getMaxAlertRecipients()) {
            throw new IllegalArgumentException("Limite de destinatários ativos atingido");
        }
        rec.setEnabled(enabled);
        return repository.save(rec);
    }

    private boolean hasDefault() {
        return properties.getDefaultRecipient() != null && !properties.getDefaultRecipient().isBlank();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
