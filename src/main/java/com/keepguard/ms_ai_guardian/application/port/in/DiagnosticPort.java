package com.keepguard.ms_ai_guardian.application.port.in;

import com.keepguard.ms_ai_guardian.application.dto.DiagnoseCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticEnqueueViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultViewDTO;

public interface DiagnosticPort {

    DiagnosticResultViewDTO diagnose(DiagnoseCommandDTO command);

    DiagnosticEnqueueViewDTO enqueue(DiagnoseCommandDTO command);
}
