# ms-ai-guardian - System Design

## 1. Propósito e Domínio
- **Responsabilidade Principal:** Agente SRE/AI que observa o cluster Kubernetes, classifica incidentes (heurística + LLM opcional), notifica destinatários e orquestra remediação (restart/rollback/scale) e fluxo de PR no GitHub quando o veredicto é defeito de código. Atua como “guardião” operacional do namespace KeepGuard.
- **Domínio/Subdomínio:** Observabilidade operacional / SRE Incident Response & AI Ops (diagnóstico K8s, lifecycle de incidentes, agentes de código/PR).

## 2. Tech Stack Local
- **Linguagem & Framework:** Java 25 / Spring Boot 3.5.3; Spring Web + Validation + AOP; Spring Cloud OpenFeign 2025.0.3; Spring AI 1.0.0-M6 (Ollama/OpenAI/Anthropic via perfis); Fabric8 Kubernetes Client 7.3.2; Resilience4j 2.2.0; Bucket4j 8.10.1; Springdoc OpenAPI 2.3.0; Lombok 1.18.40; Virtual Threads habilitadas. Artifact: `com.keepguard:ms-ai-guardian:1.0.52`. Porta HTTP: **8088**.
- **Persistência e Cache:** PostgreSQL (`keepguard_api_db`, schema JPA `ms_ai_guardian`, `ddl-auto: update`); Redis (lock distribuído, idempotência, cache de prompts/LLM, rate-limit compartilhado — prefixo `guardian`). Health de Redis desabilitado no Actuator.
- **Mensageria:** RabbitMQ (AMQP). Consome `guardian.incident.process.queue` (exchange `guardian.incident.exchange`, RK `guardian.incident.process`, com DLQ). Publica: comunicação (`ms-communication-exchange-*` / `communication.message.send`), e-mail Google (`srv-email-google-sender-exchange-*` / `email.google.send`), auditoria (`srv-audit-exchange-*` / `audit.event`).

## 3. Arquitetura Interna
- **Padrão Utilizado:** Arquitetura Hexagonal (Ports & Adapters) com traços de DDD tático — pacotes `domain`, `application` (ports in/out + use cases), `adapters` (in/out) e `infrastructure`.
- **Módulos Principais:**
  - `domain` — agregados/entidades (`Incident`, evidências, sugestões/execuções de ação, recipients, PR lifecycle, prompts, LLM invocations, classification rules) e enums (`IncidentStatus`, `IncidentSeverity`, `K8sConclusion`, `RemediationActionType`, `ClassificationVerdict`, etc.).
  - `application.port.in` — `DiagnosticPort`, `IncidentPort`, `AlertRecipientPort`, `HandlePrEventPort`.
  - `application.port.out` — persistência, K8s, GitHub, LLM, auth, cache, notification, messaging.
  - `application.service` — `AiDiagnosticService`, `DiagnosticUseCaseService`; SRE (`Incident*`, `ClusterStormService`, `AlertFanoutService`, `LlmInvestigationService`, `ActionCatalogPolicy`); agents (`Coder`, `Reviewer`, `Deployer`, `QA`, `BA`, `SoftwareArchitect`); PR (`HandlePrEventUseCase`).
  - `adapters.in` — REST (`diagnostic`, `incident`, `alertrecipient`, `github` webhook), scheduler (`KubernetesHealthWatcherScheduler`), messaging (`IncidentQueueConsumer`).
  - `adapters.out` — Feign (`AuthToken`, `LlmGateway`, `CommunicationMessage`, `GitHub`), K8s (`KubernetesInspectorService`, `KubernetesOpsAdapter`), notification e-mail, audit publisher.
  - `infrastructure` — JPA adapters/entities, Redis, rate-limit, LLM adapters (`GatewayLlmAdapter` / `SpringAiLlmAdapter`), prompts, classification catalog (`classification-rules.yml`), OAuth crypto, messaging topology, config.

## 4. Superfície de Contato (I/O)
- **Endpoints Expostos Principais:**
  - `POST /api/v1/guardian/diagnose` — diagnóstico síncrono de pod.
  - `POST /api/v1/guardian/diagnose/async` — enqueue assíncrono (`202 Accepted`; desenhado para tempestades de alertas).
  - `GET /api/v1/guardian/incidents` — listagem paginada/filtrada (default `namespace=keepguard`).
  - `GET /api/v1/guardian/incidents/{id}` — detalhe do incidente.
  - `POST /api/v1/guardian/incidents/{id}/actions` — executa ação catalogada (headers `X-User-*`, `X-Correlation-ID`).
  - `GET|PUT /api/v1/guardian/alert-recipients` e `PATCH .../alert-recipients/{id}` — gestão de destinatários de alerta.
  - `POST /api/v1/guardian/webhooks/github` — webhook de eventos de PR/comentários.
  - Actuator: `/actuator/health` (liveness/readiness), `/actuator/info`, `/actuator/prometheus`.
- **Dependências Externas:**
  - **Cluster K8s** (Fabric8) — inspeção read-mostly de pods/deployments/logs; ops de remediação (`rolloutRestart` etc.).
  - **srv-llm-gateway** (`LLM_GATEWAY_URL`, default `:8650`) — `POST /api/v1/llm/complete` (provider preferencial `gateway`).
  - **LLM legado direto** — Ollama / OpenAI / Anthropic via Spring AI (mutuamente exclusivos por flags).
  - **ms-auth** (`AUTH_BASE_URL`) — secret runtime OAuth + token client-credentials.
  - **ms-communication** — `POST /api/v1/messages/send` (Feign) e/ou exchange Rabbit de comunicação.
  - **srv-email-google-sender** — e-mail de alerta via Rabbit.
  - **srv-audit** — eventos de auditoria via Rabbit.
  - **GitHub API** — refs/trees/PRs/comentários (token `GITHUB_TOKEN`, owner `keepguard`).
  - **Redis / PostgreSQL / RabbitMQ** — infra de estado e filas.

## 5. Invariantes Locais e Observações
- LLM default é **`none`** (só classificação heurística / regras YAML); gateway e provedores pagos são opt-in via `APP_GUARDIAN_LLM_PROVIDER` e flags `*_ENABLED` — Ollama não faz pull de modelo no boot (`pull-model-strategy: never`).
- Watcher periódico (`app.guardian.watcher-enabled`, scan ~60s) no namespace configurável (default `keepguard`); anti-flapping (`anti-flapping-cooldown-minutes=15`, `healthy-streak-required=3`).
- **Cluster storm:** se % de deployments afetados ≥ limiar (default 40%, mín. 5), suprime diagnósticos individuais e trata como incidente de infra agregado (`ClusterStormService`).
- Catálogo de remediação tipado (`RECREATE_POD`, `ROLLOUT_RESTART`, `ROLLBACK_REVISION`, `SCALE_REPLAY`, `DISMISS`) com riscos LOW/HIGH/DESTRUCTIVE e regras de enable/disable em `ActionCatalogPolicy` (ex.: não recriar pod em CrashLoop/ImagePull).
- Classificação por regras prioritárias em `classification-rules.yml` (infra, tenant not found/blocked, créditos, rota operadora, code defect); `requiresCodePr=true` só para defeito de código — dispara pipeline de agentes/PR.
- Rate limits Redis/Bucket4j: GitHub 10/s, LLM 5/s, e-mail 20/s; locks/idempotência com TTLs dedicados.
- Multi-tenant operacional via `app.guardian.tenant-id` / headers `X-Company-Id` nas saídas Feign; listagem de incidentes ancora em namespace.
- Circuit breaker + retry Resilience4j em instâncias `llm-gateway` e `github-api`.
- Schema isolado `ms_ai_guardian` no mesmo DB API; Jacoco skip neste MS; Helm/K8s com profile `prod`, recursos ~1Gi limit, heap relativo ao cgroup.
- Sanitização de PII em logs (`PiiLogSanitizer`); máximo de destinatários de alerta configurável (`max-alert-recipients=20`).
