package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptCatalogPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoftwareArchitectAgentService {

    private final PromptCatalogPort prompts;

    public ArchitecturalAssessment designSolution(DiagnosticResultDTO incident, String serviceName, String targetFile,
            String rawStackTrace) {
        String error = incident != null && incident.getErrorReason() != null ? incident.getErrorReason() : "falha no fluxo";
        String file = targetFile != null && !targetFile.isBlank() ? targetFile : "camada de aplicação";
        Map<String, String> vars = Map.of(
                "serviceName", serviceName != null ? serviceName : "app",
                "file", file,
                "error", escapeMermaid(error));
        return new ArchitecturalAssessment(
                "Hotfix pontual no fluxo do incidente (sem refactor da classe)",
                prompts.render(PromptKeys.ARCH_SUMMARY, vars),
                prompts.render(PromptKeys.ARCH_CURRENT_FLOW, vars),
                prompts.render(PromptKeys.ARCH_PROPOSED_FLOW, vars)
        );
    }

    private static String escapeMermaid(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\"", "'").replace("\n", " ").replace("<", "&lt;");
    }

    public record ArchitecturalAssessment(
            String pattern,
            String summary,
            String currentFlowMermaid,
            String proposedFlowMermaid
    ) {}
}
