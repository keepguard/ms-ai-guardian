# 05 — Arquitetura alvo em Go (ms-ai-guardian-go)

Estrutura implementada (molde `ms-company-go`/`ms-auth-go`, skill `new-app-go` §4.3).

## Contextos

| Contexto | Portas in | Casos de uso | Java de origem |
|---|---|---|---|
| `incident` | `IncidentQueryUseCase`, `IncidentCommandUseCase` | `queries/incident_query_usecase.go`, `commands/execute_action_incident_usecase.go` | `IncidentQueryService`, `IncidentRemediationService` |
| `alertrecipient` | `AlertRecipientQueryUseCase`, `AlertRecipientCommandUseCase` | `queries/…`, `commands/…` + `alert_recipient_directory` | `AlertRecipientService`, `AlertRecipientUseCaseService` |
| `diagnostic` | `DiagnosticCommandUseCase` | `commands/diagnose_pod_diagnostic_usecase.go`, `enqueue_…`, fachada | `AiDiagnosticService`, `DiagnosticUseCaseService`, `IncidentEnqueueAdapter`, `IncidentQueueConsumer` |
| `watcher` | `WatcherCommandUseCase` | `commands/scan_cluster_watcher_usecase.go` | `KubernetesHealthWatcherScheduler.scanClusterHealth` |
| `pullrequest` | `PullRequestCommandUseCase` (+ `diagnostic.HotfixPipeline`) | agentes Coder/Reviewer/Deployer/Architect, resolver de arquivo | `HandlePrEventUseCase`, `agents/*` |

Serviços compartilhados da aplicação (não são portas in): `sre` (fan-out de alertas, ciclo de vida,
investigação LLM/heurística, gravação da investigação, tempestade, reconciliação), `notify` (e-mails),
`catalog` (prompts versionados e regras de classificação + seeder de boot), `llmcall` (invocação gravada).

## Regras de fronteira (verificadas por grep no fim)

- `adapters/in` ↛ `adapters/out` e vice-versa; `application` ↛ `adapters`.
- Domínio só importa stdlib, `uuid` e outros pacotes de domínio.
- Handler, consumer e agendadores recebem interface de porta in.
- Erro → HTTP só em `in/http/httperr` (`{"error","message"}` ou corpo padrão do Spring).

## Divisão do trabalho (execução)

1. Núcleo (domínio, portas, DTOs, serviços `sre`/`notify`/`catalog`/`llmcall`, contextos `incident`,
   `alertrecipient`, `diagnostic`, `watcher`, toda a borda HTTP, Postgres e composition root): implementado
   direto, como molde.
2. Em paralelo, um subagente por bloco: `pullrequest` + GitHub; cliente Kubernetes REST; Redis + RabbitMQ
   (auditoria, e-mail, fila e consumer); clientes HTTP (gateway LLM, ms-auth, ms-communication) + recursos embutidos.

## Checklist do corte

- [ ] Schema de prod confere com `db/migrations/001_ms_ai_guardian_baseline.up.sql` (dump `pg_dump -s -n ms_ai_guardian`).
- [ ] Deployment `ms-ai-guardian-go` com `DB_RUN_MIGRATIONS=false`, watcher/PR scan/consumer **desligados**.
- [ ] Comparar Go × Java: `GET /incidents?namespace=keepguard&size=20`, `GET /incidents/{id}`, `GET /alert-recipients`.
- [ ] Secret `ms-ai-guardian-secret`: chaves `GITHUB_TOKEN` (já existe) e `APP_GUARDIAN_DEFAULT_RECIPIENT`.
- [ ] Corte: selector do Service → `app=ms-ai-guardian-go`; Java `--replicas=0`; ligar as 3 flags no Go.
- [ ] Pós-corte: dashboard Go, `deploy-all-prod.sh`, conferir access log e métricas.
