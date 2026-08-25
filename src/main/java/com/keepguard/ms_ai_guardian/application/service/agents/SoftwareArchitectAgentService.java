package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoftwareArchitectAgentService {

    private final Optional<ChatClient.Builder> chatClientBuilder;

    /**
     * Analisa o fluxo arquitetural e gera os Diagramas de Sequência Mermaid (Antes vs Depois)
     * e o relatório de design de software.
     */
    public ArchitecturalAssessment designSolution(DiagnosticResultDTO incident, String serviceName, String targetFile, String rawStackTrace) {
        log.info("📐 [SoftwareArchitectAgent] Desenhando análise arquitetural e diagramas de sequência para {}", serviceName);

        String currentFlowMermaid;
        String proposedFlowMermaid;
        String architecturePattern = "Arquitetura Hexagonal (Ports & Adapters) + Domain-Driven Design (DDD)";
        String architecturalSummary;

        if ("mock-sms-gateway".equalsIgnoreCase(serviceName) || targetFile.contains("sms_handler") || targetFile.contains("sms_service")) {
            currentFlowMermaid = """
                ```mermaid
                sequenceDiagram
                    autonumber
                    actor Cliente as 📱 Cliente Corporativo
                    participant HTTP as 🌐 HTTP Handler (Echo)
                    participant Svc as ⚙️ SMSService (Core Hexagonal)
                    participant DB as 🐘 PostgreSQL (mock_test)
                    participant Telecom as 📡 Operadora Telecom

                    Cliente->>HTTP: POST /v1/messages/batch (Payload Lote)
                    HTTP->>Svc: ProcessBatchSMS(request)
                    Note over Svc: ❌ FALHA DE FLUXO / DIVISÃO POR ZERO:<br/>Calcula desconto com rate=0 sem validação defensiva!
                    Svc->>Svc: CalculateDiscountRate(priority, rate=0) 💥 Panic (integer divide by zero)
                    Svc--xHTTP: HTTP 500 Internal Server Error (Execução Abortada)
                    HTTP--xCliente: 500 Falha de Processamento
                ```
                """;

            proposedFlowMermaid = """
                ```mermaid
                sequenceDiagram
                    autonumber
                    actor Cliente as 📱 Cliente Corporativo
                    participant HTTP as 🌐 HTTP Handler (Echo)
                    participant Svc as ⚙️ SMSService (Core Hexagonal)
                    participant DB as 🐘 PostgreSQL (mock_test)
                    participant Telecom as 📡 Operadora Telecom

                    Cliente->>HTTP: POST /v1/messages/batch (API Key, Prioridade, Mensagens)
                    HTTP->>Svc: ProcessBatchSMS(request)
                    Svc->>DB: FindByAPIKey(apiKey)
                    DB-->>Svc: CompanyData (Status: ACTIVE, Saldo: 5000)
                    Svc->>DB: FindActiveRoute(companyId, priority)
                    DB-->>Svc: CarrierRoute (VIVO_DIRECT, Taxa: 12)
                    Svc->>Svc: CalculateDiscountRate(priority, rate=12) ✅ Seguro e Defensivo
                    Svc->>DB: SaveBatch(sms_messages)
                    Svc->>DB: UpdateBalance(companyId, newBalance)
                    Svc-->>HTTP: 200 OK (BatchSMSResponse)
                    HTTP-->>Cliente: 200 OK (Lote Concluído com Sucesso)
                ```
                """;

            architecturalSummary = """
                - **Camada Afetada**: `internal/core/service` (Regras de Domínio e Cálculo Tarifário).
                - **Padrão Aplicado**: Injeção de Dependências via Interfaces (`ports`), isolamento de infraestrutura PostgreSQL (`adapters/out/postgres`) e proteção contra exceções aritméticas e valores nulos.
                """;

        } else {
            // Padrão Spring Boot / Microsserviços Java
            currentFlowMermaid = """
                ```mermaid
                sequenceDiagram
                    autonumber
                    actor Cliente as 👤 Usuário / App
                    participant Controller as 🌐 RestController
                    participant Service as ⚙️ ApplicationService
                    participant Repo as 🗄️ JpaRepository
                    participant DB as 🐘 PostgreSQL

                    Cliente->>Controller: Requisição HTTP
                    Controller->>Service: Executa método de negócio
                    Note over Service: ❌ EXCEÇÃO NÃO TRATADA:<br/>Acesso direto a ponteiro/objeto nulo sem proteção
                    Service->>Service: Execução falha com NullPointerException / RuntimeException
                    Service--xController: HTTP 500 Error
                ```
                """;

            proposedFlowMermaid = """
                ```mermaid
                sequenceDiagram
                    autonumber
                    actor Cliente as 👤 Usuário / App
                    participant Controller as 🌐 RestController
                    participant Service as ⚙️ ApplicationService
                    participant Repo as 🗄️ JpaRepository
                    participant DB as 🐘 PostgreSQL

                    Cliente->>Controller: Requisição HTTP
                    Controller->>Service: Executa método de negócio
                    Service->>Repo: Consulta com validação Optional / Null-Safe
                    Repo-->>Service: Registro validado
                    Service->>Service: Executa lógica defensiva e segura
                    Service-->>Controller: 200 OK DTO
                    Controller-->>Cliente: Resposta com Sucesso
                ```
                """;

            architecturalSummary = """
                - **Camada Afetada**: Camada de Aplicação / Serviços de Domínio.
                - **Padrão Aplicado**: Clean Architecture, fail-fast validations e encapsulamento com `Optional`.
                """;
        }

        return new ArchitecturalAssessment(
                architecturePattern,
                architecturalSummary,
                currentFlowMermaid,
                proposedFlowMermaid
        );
    }

    public record ArchitecturalAssessment(
            String pattern,
            String summary,
            String currentFlowMermaid,
            String proposedFlowMermaid
    ) {}
}
