package com.keepguard.ms_ai_guardian.application.port.out.messaging;

import com.keepguard.ms_ai_guardian.application.dto.DiagnoseCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticEnqueueViewDTO;

public interface IncidentEnqueuePort {

    DiagnosticEnqueueViewDTO enqueue(DiagnoseCommandDTO command);
}
