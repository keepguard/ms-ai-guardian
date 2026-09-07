package com.keepguard.ms_ai_guardian.application.service;

import com.keepguard.ms_ai_guardian.application.dto.DiagnoseCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticEnqueueViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultViewDTO;
import com.keepguard.ms_ai_guardian.application.port.in.DiagnosticPort;
import com.keepguard.ms_ai_guardian.application.port.out.messaging.IncidentEnqueuePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DiagnosticUseCaseService implements DiagnosticPort {

    private final AiDiagnosticService commandService;
    private final IncidentEnqueuePort incidentEnqueuePort;

    @Override
    public DiagnosticResultViewDTO diagnose(DiagnoseCommandDTO command) {
        return commandService.diagnosePod(
                command.getNamespace(),
                command.getPodName(),
                command.getServiceName(),
                command.getErrorReason(),
                command.isForceSendEmail());
    }

    @Override
    public DiagnosticEnqueueViewDTO enqueue(DiagnoseCommandDTO command) {
        return incidentEnqueuePort.enqueue(command);
    }
}
