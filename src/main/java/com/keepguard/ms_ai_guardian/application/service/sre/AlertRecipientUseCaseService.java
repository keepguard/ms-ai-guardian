package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientUpsertCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientViewDTO;
import com.keepguard.ms_ai_guardian.application.mapper.GuardianApplicationMapper;
import com.keepguard.ms_ai_guardian.application.port.in.AlertRecipientPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertRecipientUseCaseService implements AlertRecipientPort {

    private final AlertRecipientService alertRecipientService;
    private final GuardianApplicationMapper mapper;
    private final GuardianAuditPublisher auditPublisher;

    @Override
    public List<AlertRecipientViewDTO> list() {
        return alertRecipientService.listAll().stream().map(mapper::toAlertRecipientView).toList();
    }

    @Override
    public AlertRecipientViewDTO upsert(AlertRecipientUpsertCommandDTO command) {
        boolean enabled = command.getEnabled() == null || command.getEnabled();
        AlertRecipientViewDTO view = mapper.toAlertRecipientView(
                alertRecipientService.upsert(command.getEmail(), command.getLabel(), enabled));
        publishRecipientAudit("GUARDIAN_ALERT_RECIPIENT_UPSERTED", view);
        return view;
    }

    @Override
    public AlertRecipientViewDTO patch(UUID id, AlertRecipientUpsertCommandDTO command) {
        boolean enabled = command.getEnabled() == null || command.getEnabled();
        AlertRecipientViewDTO view = mapper.toAlertRecipientView(alertRecipientService.setEnabled(id, enabled));
        publishRecipientAudit("GUARDIAN_ALERT_RECIPIENT_PATCHED", view);
        return view;
    }

    private void publishRecipientAudit(String action, AlertRecipientViewDTO view) {
        String codeUser = blankToNull(MDC.get("codeUser"));
        String actorType = codeUser != null ? "USER" : "SYSTEM";
        String resourceId = view.getId() != null ? view.getId().toString() : "";
        auditPublisher.publish(action, "SUCCESS", MDC.get("correlationId"), "ALERT_RECIPIENT", resourceId,
                actorType, codeUser);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
