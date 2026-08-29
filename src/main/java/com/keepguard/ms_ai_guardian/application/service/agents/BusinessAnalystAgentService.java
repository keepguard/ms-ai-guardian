package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.domain.classification.BusinessVerdict;
import com.keepguard.ms_ai_guardian.domain.classification.ClassificationEngine;
import com.keepguard.ms_ai_guardian.infrastructure.classification.ClassificationCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessAnalystAgentService {

    private final ClassificationCatalog catalog;

    public BusinessVerdict evaluateIncident(DiagnosticResultDTO incident, String recentLogs) {
        log.info("[BusinessAnalystAgent] Classificando {} (erro: {})",
                incident.getServiceName(), incident.getErrorReason());
        return ClassificationEngine.evaluate(
                catalog.activeRules(),
                incident.getErrorReason(),
                recentLogs);
    }
}
