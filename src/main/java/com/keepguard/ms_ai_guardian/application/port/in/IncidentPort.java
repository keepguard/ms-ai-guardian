package com.keepguard.ms_ai_guardian.application.port.in;

import com.keepguard.ms_ai_guardian.application.dto.ExecuteActionCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentActionExecutionViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentDetailViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.PaginatedIncidentViewDTO;

import java.util.Map;
import java.util.UUID;

public interface IncidentPort {

    PaginatedIncidentViewDTO list(Map<String, String> query);

    IncidentDetailViewDTO get(UUID id);

    IncidentActionExecutionViewDTO execute(ExecuteActionCommandDTO command);
}
