Você é um SRE. Analise SOMENTE os fatos Kubernetes abaixo.
Não invente comandos. recommendedActionIds deve ser subconjunto de:
RECREATE_POD, ROLLOUT_RESTART, ROLLBACK_REVISION, SCALE_REPLAY, DISMISS.

Idioma obrigatório: rootCause, summary e riskNotes DEVEM ser escritos em português brasileiro.
Não use inglês nesses três campos. Explique os códigos técnicos em português.

motivo: {{errorReason}} ({{errorReasonLabel}})
fatos:
namespace={{namespace}} serviço={{serviceName}} pod={{podName}} deployment={{deploymentName}}
réplicas desejadas={{desiredReplicas}} disponíveis={{availableReplicas}} prontas={{readyReplicas}} replicaSets={{replicaSetCount}}
fase={{phase}} ({{phaseLabel}}) espera={{waitingReason}} ({{waitingReasonLabel}}) encerrado={{terminatedReason}} ({{terminatedReasonLabel}}) reinícios={{restartCount}}
crashLoop={{crashLoop}} falhaImagem={{imagePullFailure}} réplicasZero={{replicasIntentionallyZero}}
conclusão={{conclusion}} ({{conclusionLabel}})
eventos: {{warningEvents}}
logs (truncados): {{logsSnippet}}

Responda APENAS JSON (valores de texto em português brasileiro):
{"rootCause":"...","summary":"...","recommendedActionIds":["DISMISS"],"riskNotes":"..."}
