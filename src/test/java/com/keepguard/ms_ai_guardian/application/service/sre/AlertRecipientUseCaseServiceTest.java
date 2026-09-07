package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.adapters.out.audit.GuardianAuditPublisher;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientUpsertCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientViewDTO;
import com.keepguard.ms_ai_guardian.application.mapper.GuardianApplicationMapper;
import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertRecipientUseCaseServiceTest {

    @Mock
    private AlertRecipientService alertRecipientService;
    @Mock
    private GuardianApplicationMapper mapper;
    @Mock
    private GuardianAuditPublisher auditPublisher;

    @InjectMocks
    private AlertRecipientUseCaseService service;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void upsertPublishesUserActorWhenCodeUserPresent() {
        UUID id = UUID.randomUUID();
        GuardianAlertRecipient saved = GuardianAlertRecipient.builder().id(id).email("ops@keepguard.io").enabled(true).build();
        AlertRecipientViewDTO view = AlertRecipientViewDTO.builder().id(id).email("ops@keepguard.io").enabled(true).build();
        when(alertRecipientService.upsert("ops@keepguard.io", "ops", true)).thenReturn(saved);
        when(mapper.toAlertRecipientView(saved)).thenReturn(view);
        MDC.put("codeUser", "user-1");
        MDC.put("correlationId", "corr-1");

        service.upsert(AlertRecipientUpsertCommandDTO.builder().email("ops@keepguard.io").label("ops").enabled(true).build());

        verify(auditPublisher).publish(eq("GUARDIAN_ALERT_RECIPIENT_UPSERTED"), eq("SUCCESS"), eq("corr-1"),
                eq("ALERT_RECIPIENT"), eq(id.toString()), eq("USER"), eq("user-1"));
    }

    @Test
    void patchPublishesSystemActorWithoutCodeUser() {
        UUID id = UUID.randomUUID();
        GuardianAlertRecipient saved = GuardianAlertRecipient.builder().id(id).email("ops@keepguard.io").enabled(false).build();
        AlertRecipientViewDTO view = AlertRecipientViewDTO.builder().id(id).enabled(false).build();
        when(alertRecipientService.setEnabled(id, false)).thenReturn(saved);
        when(mapper.toAlertRecipientView(saved)).thenReturn(view);

        service.patch(id, AlertRecipientUpsertCommandDTO.builder().enabled(false).build());

        verify(auditPublisher).publish(eq("GUARDIAN_ALERT_RECIPIENT_PATCHED"), eq("SUCCESS"), isNull(),
                eq("ALERT_RECIPIENT"), eq(id.toString()), eq("SYSTEM"), isNull());
    }
}
