package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoftwareArchitectAgentService {

    private final Optional<org.springframework.ai.chat.client.ChatClient.Builder> chatClientBuilder;

    public ArchitecturalAssessment designSolution(DiagnosticResultDTO incident, String serviceName, String targetFile,
            String rawStackTrace) {
        log.info("📐 [SoftwareArchitectAgent] Desenhando análise a partir do incidente em {}", targetFile);

        String error = incident != null && incident.getErrorReason() != null ? incident.getErrorReason() : "falha no fluxo";
        String file = targetFile != null && !targetFile.isBlank() ? targetFile : "camada de aplicação";

        String currentFlowMermaid = """
                ```mermaid
                sequenceDiagram
                    autonumber
                    actor Cliente
                    participant App as %s
                    participant Alvo as `%s`

                    Cliente->>App: Requisição
                    App->>Alvo: Executa fluxo
                    Note over Alvo: ❌ %s
                    Alvo--xApp: Falha
                    App--xCliente: Erro
                ```
                """.formatted(serviceName, file, escapeMermaid(error));

        String proposedFlowMermaid = """
                ```mermaid
                sequenceDiagram
                    autonumber
                    actor Cliente
                    participant App as %s
                    participant Alvo as `%s`

                    Cliente->>App: Requisição
                    App->>Alvo: Executa fluxo com guarda defensiva
                    Note over Alvo: ✅ Hotfix pontual do incidente
                    Alvo-->>App: Sucesso
                    App-->>Cliente: Resposta OK
                ```
                """.formatted(serviceName, file);

        String architecturalSummary = """
                - **Arquivo afetado:** `%s`
                - **Incidente:** %s
                - **Abordagem:** hotfix só no fluxo que falhou; demais caminhos do arquivo permanecem.
                """.formatted(file, error);

        return new ArchitecturalAssessment(
                "Hotfix pontual no fluxo do incidente (sem refactor da classe)",
                architecturalSummary,
                currentFlowMermaid,
                proposedFlowMermaid
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
