package com.keepguard.ms_ai_guardian.domain.enums;

public enum IncidentStatus {
    QUEUED,                  // 1. Recebido e enfileirado no RabbitMQ
    RATE_LIMITED_WAITING,    // 2. Aguardando liberação na fila de vazão
    EVALUATING_BUSINESS,     // 3. BusinessAnalystAgent inspecionando banco e regras
    GENERATING_ARCHITECTURE, // 4. SoftwareArchitectAgent desenhando diagramas Mermaid
    CODING_HOTFIX,           // 5. CoderAgent gerando branch e código
    QA_CERTIFYING,           // 6. QaAutomationAgent executando testes
    PR_OPENED,               // 7. Pull Request aberto no GitHub
    AWAITING_HUMAN_APPROVAL, // 8. Aguardando aprovação/merge do Rafael
    DEPLOYING_K8S,           // 9. DeployerAgent executando rollout no cluster
    RESOLVED,                // 10. Incidente totalmente corrigido e validado
    REJECTED_DATA_ISSUE,     // 11. Rejeitado para código (era falha de dados no banco)
    FAILED_DLQ,              // 12. Falha irrecuperável (enviado para Dead Letter Queue)
    DETECTED,
    DIAGNOSING,
    DIAGNOSED,
    NOTIFIED,
    AWAITING_HUMAN,
    ACTION_RUNNING,
    NORMALIZED,
    DISMISSED,
    IGNORED
}
