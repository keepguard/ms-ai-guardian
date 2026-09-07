package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.ExecuteActionCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentActionExecutionViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.IncidentDetailViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.PaginatedIncidentViewDTO;
import com.keepguard.ms_ai_guardian.application.mapper.GuardianApplicationMapper;
import com.keepguard.ms_ai_guardian.application.port.in.IncidentPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentUseCaseService implements IncidentPort {

    private final IncidentQueryService queryService;
    private final IncidentRemediationService commandService;
    private final GuardianApplicationMapper mapper;

    @Override
    public PaginatedIncidentViewDTO list(Map<String, String> query) {
        return queryService.list(query);
    }

    @Override
    public IncidentDetailViewDTO get(UUID id) {
        return queryService.get(id);
    }

    @Override
    public IncidentActionExecutionViewDTO execute(ExecuteActionCommandDTO command) {
        return mapper.toExecutionView(commandService.execute(
                command.getIncidentId(),
                command.getSuggestionId(),
                command.getConfirmation(),
                command.getActorUserId(),
                command.getActorEmail(),
                command.getActorRole(),
                command.getCorrelationId()));
    }
}
