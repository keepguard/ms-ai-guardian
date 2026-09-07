package com.keepguard.ms_ai_guardian.application.port.in;

import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientUpsertCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientViewDTO;

import java.util.List;
import java.util.UUID;

public interface AlertRecipientPort {

    List<AlertRecipientViewDTO> list();

    AlertRecipientViewDTO upsert(AlertRecipientUpsertCommandDTO command);

    AlertRecipientViewDTO patch(UUID id, AlertRecipientUpsertCommandDTO command);
}
