package com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic;

import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.request.ManualDiagnoseRequestDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.response.DiagnosticEnqueueResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.response.DiagnosticResultResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.mapper.DiagnosticAdapterMapper;
import com.keepguard.ms_ai_guardian.application.port.in.DiagnosticPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/guardian")
@RequiredArgsConstructor
@Tag(name = "AI Guardian Diagnostics", description = "Diagnóstico síncrono e ingestão assíncrona de incidentes")
public class DiagnosticController {

    private final DiagnosticPort diagnosticPort;
    private final DiagnosticAdapterMapper mapper;

    @PostMapping("/diagnose/async")
    @Operation(summary = "Ingestão assíncrona ultra-rápida (202 Accepted) para suportar tempestades de 1.000+ alertas/minuto")
    public ResponseEntity<DiagnosticEnqueueResponseDTO> triggerDiagnosisAsync(
            @RequestBody ManualDiagnoseRequestDTO request) {
        return ResponseEntity.accepted().body(mapper.toEnqueueResponse(
                diagnosticPort.enqueue(mapper.toAsyncCommand(request))));
    }

    @PostMapping("/diagnose")
    @Operation(summary = "Acionar diagnóstico inteligente sob demanda para um Pod (Síncrono)")
    public ResponseEntity<DiagnosticResultResponseDTO> triggerDiagnosis(
            @RequestBody ManualDiagnoseRequestDTO request) {
        return ResponseEntity.ok(mapper.toResponse(diagnosticPort.diagnose(mapper.toCommand(request))));
    }
}
