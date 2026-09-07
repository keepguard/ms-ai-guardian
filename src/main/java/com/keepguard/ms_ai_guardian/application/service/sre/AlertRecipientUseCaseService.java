package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientUpsertCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientViewDTO;
import com.keepguard.ms_ai_guardian.application.mapper.GuardianApplicationMapper;
import com.keepguard.ms_ai_guardian.application.port.in.AlertRecipientPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertRecipientUseCaseService implements AlertRecipientPort {

    private final AlertRecipientService alertRecipientService;
    private final GuardianApplicationMapper mapper;

    @Override
    public List<AlertRecipientViewDTO> list() {
        return alertRecipientService.listAll().stream().map(mapper::toAlertRecipientView).toList();
    }

    @Override
    public AlertRecipientViewDTO upsert(AlertRecipientUpsertCommandDTO command) {
        boolean enabled = command.getEnabled() == null || command.getEnabled();
        return mapper.toAlertRecipientView(
                alertRecipientService.upsert(command.getEmail(), command.getLabel(), enabled));
    }

    @Override
    public AlertRecipientViewDTO patch(UUID id, AlertRecipientUpsertCommandDTO command) {
        boolean enabled = command.getEnabled() == null || command.getEnabled();
        return mapper.toAlertRecipientView(alertRecipientService.setEnabled(id, enabled));
    }
}
