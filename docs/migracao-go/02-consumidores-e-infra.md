# 02 — Consumidores e infraestrutura de produção (ms-ai-guardian)

Levantamento da Fase 0 (somente leitura), feito em 2026-10-04. Caminhos relativos a
`keepguard-core/`, salvo quando indicado. Comandos `kubectl` usaram
`KUBECONFIG=~/.kube/kubeconfig-hostinger`, namespace `keepguard`.

## Resumo

- **Único consumidor HTTP: o `bff-core`.** O backoffice (`frontend/backoffice`) passa por ele.
  Nada em `investbot/`, `achadinhos/`, Alertmanager ou GitHub chama o guardian.
- **Tráfego real é mínimo.** Desde que o pod subiu (22 dias): 44 `GET /incidents`, 2 `GET /incidents/{id}`,
  8 `GET /alert-recipients`. Nenhum `POST /actions`, `PUT`/`PATCH` de destinatários, `/diagnose` ou webhook.
  Nos últimos 7 dias, nenhuma chamada (Prometheus, §3.7).
- **O trabalho de verdade é o watcher.** A cada ~60 s ele varre o namespace `keepguard`, abre ou atualiza
  incidentes e manda e-mail e auditoria pelo RabbitMQ.
- **LLM desligado em prod** (`APP_GUARDIAN_LLM_PROVIDER=none`). Só a heurística roda.
  `llm_invocations` e `pull_request_lifecycles` estão vazias.
- **Webhook do GitHub não tem exposição externa.** Não há Ingress nem IngressRoute para
  `/api/v1/guardian/webhooks/github`, e `processed_comments` está vazia.
- **Achados graves fora do guardian:** veja §6. Os e-mails do guardian estão sem consumidor desde
  2026-09-27, e há um token do GitHub em texto puro no Deployment.

---

## 1. Consumidores HTTP

### 1.1 Busca no monorepo

`grep -rIl -E "ms-ai-guardian|ai-guardian|ms_ai_guardian|/api/v1/guardian|:8088"` fora do próprio serviço
encontrou só:

| Onde | O que é |
|---|---|
| `backend/bff/bff-core/**` | cliente HTTP real (§1.2) |
| `frontend/backoffice/src/services/guardianService.ts` | chama o **bff-core**, não o guardian (§1.3) |
| `k8s/observability/{prometheus-configmap.yaml,alerting/rules.yaml,generate-dashboards.py,dashboards/ms-ai-guardian.json}` | scrape, alerta e dashboard (§5) |
| `docker/docker-compose.yml:534-590`, `docker/.env:95,114`, `docker/prometheus/prometheus.yml:78-83` | só ambiente local |
| `scripts/deploy-all-prod.sh:77`, `scripts/sync-apps-data-to-prod.sh:42,60`, `scripts/tunnel-prod-services-all.sh:31`, `scripts/create-llm-oauth-clients.sh:58-60,188` | scripts operacionais |
| `backend/ms/ms-auth-go/internal/domain/role/*.go`, `ms-auth/.../SystemAuthorityNames.java` | authorities `guardian:read`/`guardian:write` (são strings de permissão, não chaves Redis) |
| `backend/srv/srv-email-sender/README.md:5`, `docs/architecture/system-design.md:34` | contrato RabbitMQ "imutável para o AI Guardian" (§2.2) |

`investbot/` e `achadinhos/`: nenhuma ocorrência. Alertmanager, Prometheus e Grafana não chamam o
guardian (nenhum `diagnose`, `webhooks/github` ou receiver de webhook). Na busca por `alertmanager`,
nada aponta para o guardian.

### 1.2 bff-core (Go) — consumidor único

**Configuração**
- Base URL em prod: `http://ms-ai-guardian:8088`, timeout `20s`, `retries: 2`
  (`backend/bff/bff-core/application-prod.yml:37-40`). Defaults em `internal/infrastructure/config/config.go:241-243`.
- Rate limit `guardian`: 40 req/60 s em prod (`application-prod.yml:113-115`), default 60 (`config.go:287-288`).
  Esse mesmo balde também cobre `/core/oauth/*` (`internal/adapters/inbound/http/server.go:171-178`).
- Health do catálogo de conexões: `GET http://ms-ai-guardian:8088/actuator/health/liveness`
  (`application-prod.yml:160`; `internal/application/connections/catalog.go:41`).
  **O Go precisa responder `/actuator/health/liveness`.**
- Nenhum env de guardian no Deployment vivo do bff-core. Vale o YAML de prod (consulta que procurou
  `guardian` ou `8088` nos envs de todos os Deployments).

**Rotas expostas pelo BFF e repassadas ao guardian** (`server.go:114` cria o grupo `/api/v1`; rotas em `server.go:150-166`)

| BFF (público) | Permissão | Guardian (interno) | Cliente |
|---|---|---|---|
| `GET /api/v1/core/guardian/incidents` | `guardian:read` | `GET /api/v1/guardian/incidents` | `client/guardian_client.go:37-57` |
| `GET /api/v1/core/guardian/incidents/:id` | `guardian:read` | `GET /api/v1/guardian/incidents/{id}` | `guardian_client.go:59-74` |
| `POST /api/v1/core/guardian/incidents/:id/actions` | `guardian:write` + billingRestricted | `POST /api/v1/guardian/incidents/{id}/actions` | `guardian_client.go:76-95` |
| `GET /api/v1/core/guardian/alert-recipients` | `guardian:read` | `GET /api/v1/guardian/alert-recipients` | `guardian_client.go:97-112` |
| `PUT /api/v1/core/guardian/alert-recipients` | `guardian:write` | `PUT /api/v1/guardian/alert-recipients` | `guardian_client.go:114-130` |
| `PATCH /api/v1/core/guardian/alert-recipients/:id` | `guardian:write` | `PATCH /api/v1/guardian/alert-recipients/{id}` | `guardian_client.go:132-148` |

Permissões: `middleware/roles_middleware.go:35-36,86-91`.

O BFF **não** consome `POST /api/v1/guardian/diagnose`, `POST /api/v1/guardian/diagnose/async` nem
`POST /api/v1/guardian/webhooks/github` (`DiagnosticController.java:26,34`; `GitHubWebhookController.java:19,27`).
Nenhum outro serviço os consome.

**Headers enviados ao guardian**
- Todas as rotas: `X-Tenant-Id` (tenant do JWT) e `X-Correlation-ID` (`guardian_client.go:41-42,63-64,...`).
- `POST /actions` também manda `X-User-ID`, `X-User-Email` e `X-User-Role` (o primeiro role do JWT)
  (`guardian_client.go:82-84`; montados em `handlers/guardian_handlers.go:90-107`).
- **Não manda `Authorization`.** O guardian não tem Spring Security (não há `SecurityFilterChain`
  nem starter de security no `pom.xml`), então confia na rede do cluster.
- **O guardian ignora `X-Tenant-Id`.** Nenhum controller lê esse header (`IncidentController.java:41-74`,
  `AlertRecipientController.java:28-44`). Os dados são globais, sem isolamento por tenant.
- `X-Correlation-ID` só é lido em `POST /actions` (`IncidentController.java:71`). Não existe filtro que
  coloque o correlation id ou o `codeUser` no MDC (grep por `MDC.put` e `OncePerRequestFilter` sem
  resultado). Por isso as auditorias de destinatário saem com correlation id aleatório e ator `SYSTEM`
  (`AlertRecipientUseCaseService.java:45-50`; `GuardianAuditPublisher.java:45-47`).

**Query params repassados** em `GET /incidents` (`guardian_handlers.go:34-37`):
`page, size, from, to, status, severity, serviceName, namespace, k8sConclusion, errorReason, correlationId, q, sort, dir`.
É a mesma whitelist do Java (`IncidentController.java:33-36`), que aplica `namespace=keepguard`
como default (`IncidentController.java:53`).

**Bodies enviados** (`internal/application/dto/guardian.go:11-20`)
- Ações: `{"suggestionId": "...", "confirmation": "..."}`. `confirmation` tem `omitempty`.
- Destinatário: `{"email": "...", "label": "...", "enabled": true}`. `label` e `enabled` têm `omitempty`;
  **`email` não tem**. No PATCH, o front manda só `{enabled}` (`guardianService.ts:133-138`) e o BFF
  repassa `"email": ""`. O Java ignora o e-mail no patch (`AlertRecipientUseCaseService.java:38-43`).
  O Go precisa manter esse comportamento.

**Campos lidos na resposta**
- `GET /incidents`: struct tipada só no envelope `content, page, size, totalElements, totalPages`
  (`dto/guardian.go:3-9`). `content` é `[]map[string]any`, repassado sem tocar.
- Demais rotas: `map[string]any` ou `[]map[string]any`, repassados sem tocar
  (`guardian_client.go:59-148`). Quem lê os campos é o front (§1.3).
- Sucesso exige **status 200** exato. Qualquer outro status vira erro (`guardian_client.go:53,70,91,108,126,144`).

**Tratamento de erro**
- Status diferente de 200: `MapHTTPError` (`client/error_mapper.go:24-44`) lê `message`, `error` e `errors`
  do corpo e preserva o status. Sem mensagem, usa um texto padrão por status (`error_mapper.go:86-113`).
  **Não lê ProblemDetail** (`title`/`detail`). Se o Go responder ProblemDetail, o usuário vê o texto padrão.
- Erro de rede: `MapNetworkError` → "erro ao comunicar com serviço Guardian" (`error_mapper.go:117-119`; `internal/pkg/user_messages.go:47`).
- Retry: o resty tenta de novo uma vez em erro de rede (`guardian_client.go:28-29`). O decorator
  `observe.Call` faz até 2 tentativas em 5xx ou erro de rede (`decorator/observe/observe.go:22-30,119-131`;
  aplicado em `decorator/guardian/decorate.go:18-59`). Isso vale **também para `POST /actions`**: uma
  ação pode ser repetida quando o guardian responde 5xx. A idempotência do lado do guardian precisa
  ser confirmada no levantamento de especificação (01).
- Handler sem cliente (`guardian == nil`): 503 `SERVICE_UNAVAILABLE` "Guardian indisponível" (`guardian_handlers.go:198-202`).
- Auditoria do BFF classifica rotas `/core/guardian` (`middleware/audit_middleware.go:116`).
- Alerta `bff-core` 5xx em `/api/v1/core/(...|guardian|...)` (`k8s/observability/alerting/rules.yaml:904,928`).

### 1.3 Backoffice (`frontend/backoffice`) — consumidor indireto, via bff-core

- Serviço: `src/services/guardianService.ts:76-139`. Chama somente `${BFF_CORE_URL}/api/v1/core/guardian/...`.
- Tela: `src/components/dashboard/GuardianView.tsx`. Rota `/guardian` (`src/navigation/routes.ts:40`;
  `AppRoutes.tsx:104-107`), visível com `guardian:read` (`src/utils/roles.ts:120-144`; `Sidebar.tsx:72,208-211`).
- O front sempre manda `size=20` e `namespace=keepguard` (`GuardianView.tsx:199-201`).
  Ordenações usadas: `lastSeenAt, createdAt, severity, status, serviceName` (`GuardianView.tsx:30,450-454`).
  Filtros de status oferecidos: `AWAITING_HUMAN, ACTION_RUNNING, NOTIFIED, DETECTED, NORMALIZED, DISMISSED`
  (`GuardianView.tsx:396-401`). Severidades: `CRITICAL, HIGH, MEDIUM, LOW, INFO` (`GuardianView.tsx:427-431`).
  `from`/`to` saem como ISO UTC com `Z` e sem milissegundos (`GuardianView.tsx:77-82`).

**Campos que o front de fato lê**

| Resposta | Campos lidos | Declarados, mas não lidos |
|---|---|---|
| lista (`content[]`) | `id, serviceName, podName, status, severity, k8sConclusion, occurrencesCount, emailSent, lastSeenAt, createdAt` (`GuardianView.tsx:73-75,532-582`) e `totalPages` (`:215`) | `namespace, errorReason, normalizedAt`, `page, size, totalElements` |
| detalhe | `incident.{id, serviceName, status, severity, podName, occurrencesCount, k8sConclusion, errorReason, lastSeenAt/createdAt}`, `investigationSource, healthyStreak, correlationId, aiSummary, aiRootCause` (fallback), `aiRecommendedAction, capturedLogsSnippet`, `evidence[].{id,kind,payloadJson,createdAt}`, `timeline[].{eventType,detail,createdAt}`, `executions[].{id,createdAt,outcome,actorUserId,errorMessage}`, `deliveries[].{email,outcome,kind,sentAt}`, `suggestions[].{id,actionType,label,risk,enabled,disabledReason,aiRationale}` (`GuardianView.tsx:644-817`) | `suggestions[].payloadJson`, `executions[].suggestionId` (enviados pelo Java: `IncidentDetailResponseDTO.java:46-68`) |
| `POST /actions` | nada (`Promise<unknown>`); recarrega o detalhe e a lista (`GuardianView.tsx:306-316`) | todo o `IncidentActionExecutionResponseDTO` |
| destinatários | `id, email, enabled` (`GuardianView.tsx:614-630`) | `label, createdAt, updatedAt` |

Regras de UI que dependem de valores de enum: `risk === 'DESTRUCTIVE'` exige digitar o `serviceName`
(`GuardianView.tsx:297-311`). `actionType === 'DISMISS'` fica sempre habilitado (`:786`).
`investigationSource` `LLM`/`HEURISTIC_FALLBACK` (`:145-154`).

Datas: o Java serializa `LocalDateTime` (`IncidentListItemResponseDTO.java:28-30`), e o front faz
`new Date(iso)` (`GuardianView.tsx:57-71`). O formato ISO sem fuso precisa ser mantido (é o `jsontime`
da skill).

**Erros no front:** `customFetch` monta a mensagem com `data.message || data.detail || data.error`
(`src/services/api.ts:190-195`), e a tela mostra toast com `error.message` (`GuardianView.tsx:216-221,272-277,317-322,334-339`).
Erro ao listar destinatários vira lista vazia, sem aviso (`GuardianView.tsx:233-235`).

---

## 2. Contratos fora do HTTP

### 2.1 RabbitMQ consumido pelo guardian

| Item | Valor | Fonte |
|---|---|---|
| Exchange | `guardian.incident.exchange` (topic, durable) | `infrastructure/messaging/RabbitMqTopologyConfig.java:12,21-23` |
| Fila | `guardian.incident.process.queue` (durable, DLX = mesma exchange, DL-RK `guardian.incident.process.dlq.rk`) | `RabbitMqTopologyConfig.java:14,26-31` |
| Routing key | `guardian.incident.process` | `:15,40-42` |
| DLQ | `guardian.incident.process.dlq` | `:17-18,34-47` |
| Retry | stateless, 3 tentativas, backoff 1 s ×2 até 5 s, depois `RepublishMessageRecoverer` para a DLQ | `:65-93` |
| Payload | `IncidentQueueMessage {trackingId: UUID, namespace, podName, serviceName, errorReason, forceSendEmail: bool, enqueuedTimestamp: long ms}`, Jackson2Json | `messaging/dto/IncidentQueueMessage.java:15-23` |
| Listener | `IncidentQueueConsumer.consumeIncident`: pega permissão do rate limiter e chama `AiDiagnosticService.diagnosePod`; em erro, relança (vai para retry e depois DLQ) | `adapters/in/messaging/IncidentQueueConsumer.java:20-44` |

**Quem publica:** só o próprio guardian, via `POST /api/v1/guardian/diagnose/async`
(`IncidentEnqueueAdapter.java:20-43`). Nenhum outro serviço do monorepo publica em `guardian.incident.*`.
Em prod, a fila e a DLQ estão vazias, com **0 mensagens publicadas** e 1 consumidor (exporter RabbitMQ:
`rabbitmq_queue_messages`, `rabbitmq_queue_messages_published_total` e `rabbitmq_queue_consumers` para
`queue=~"guardian.*"`). **A rota assíncrona e a fila nunca foram usadas em prod.**

O Jackson2JsonMessageConverter grava o header `__TypeId__=com.keepguard...IncidentQueueMessage`.
Durante a convivência Java × Go isso não importa, porque só o guardian publica. Mas o Go não pode
consumir a mesma fila ao mesmo tempo que o Java.

### 2.2 RabbitMQ publicado pelo guardian

**a) E-mail → `srv-email-sender` (Go)**
- Exchange `app.rabbitmq.email-exchange`, default `srv-email-google-sender-exchange-dev`; routing key
  `email.google.send` (`EmailNotificationService.java:39-43`; `application.yml:130-131`).
  `application-prod.yml` **não** sobrescreve, e o Deployment não define env para isso (§3.2).
  **Em prod o guardian publica no exchange `-dev`.**
- Payload, um Map em JSON (`EmailNotificationService.java:298-306`):
  `{"tenant_id", "x_correlation_id", "correlationId", "to", "subject", "html"}`. `to` cai no
  `default-recipient` quando está vazio (`:297`). `tenant_id` vem de `APP_GUARDIAN_TENANT_ID`, que
  **não está definido em prod** e por isso vai vazio.
- O consumidor espera `EmailQueueMessage` (`backend/srv/srv-email-sender/internal/application/dto/send_email_command.go:33-49`),
  que aceita `to, subject, html, tenant_id|tenantId, correlationId|x_correlation_id|xCorrelationId|correlation_id, codeUser|code_user`.
  Prioridade do correlation id: `correlationId` > `x_correlation_id` (`:60-69`). É compatível.
- Rate limit antes de publicar: `rateLimiter.acquireEmailPermit()` (`:296`), com 20/s (`application.yml:113`).
- **Fallback HTTP** quando a publicação falha (`:248-291`): veja §2.3.

**b) Auditoria → `srv-audit` (Go)**
- Exchange `keepguard.audit.exchange`, que em prod é `srv-audit-exchange-prod` (env `KEEPGUARD_AUDIT_EXCHANGE`
  no Deployment e `application-prod.yml:22-24`). Routing key `audit.event`. Declara a exchange topic durable
  uma única vez. Envio assíncrono (`CompletableFuture`), persistente, com header `X-Correlation-ID`;
  falhas são engolidas com `warn` (`adapters/out/audit/GuardianAuditPublisher.java:30-98`).
- Envelope (`:48-74`): `eventId, occurredAt (Instant ISO), schemaVersion=1, sourceService="ms-ai-guardian",
  correlationId, tenantId?/companyId? (do MDC, que nunca é preenchido), action, outcome,
  actor{type, codeUser?}, resource{type, id}`.
- O consumidor espera `AuditEventMessage` (`backend/srv/srv-audit/internal/application/dto/audit_event.go:13-49`).
  É compatível. O srv-audit em prod (`APP_ENV=prod`) consome `srv.audit.events.prod`, ligada a
  `srv-audit-exchange-prod` com RK `audit.event` (`backend/srv/srv-audit/application-prod.yml:13-23`).
- Actions publicadas: `GUARDIAN_INCIDENT_OPENED` (`AiDiagnosticService.java:94`),
  `GUARDIAN_INCIDENT_NORMALIZED` (`IncidentReconciliationService.java:110`),
  `GUARDIAN_REMEDIATION_REQUESTED|APPLIED|FAILED`, `GUARDIAN_INCIDENT_DISMISSED` (`IncidentRemediationService.java:69-105`),
  `GUARDIAN_CLUSTER_STORM_OPENED` (`ClusterStormService.java:163`),
  `GUARDIAN_ALERT_RECIPIENT_UPSERTED|PATCHED` (`AlertRecipientUseCaseService.java:33,41`),
  `GUARDIAN_ALERT_SENT|FAILED` (`EmailNotificationService.java:61,256`),
  `GUARDIAN_GITHUB_WEBHOOK` (`HandlePrEventUseCase.java:102`).
- Ao todo, o guardian publicou 248 mensagens desde que o pod subiu, com 0 falhas
  (`rabbitmq_published_total{application="ms-ai-guardian"}`, `rabbitmq_failed_to_publish_total`).

**c) `ms-communication` por RabbitMQ: não existe.** `RabbitMQConfig.java:14-20` só **declara** a exchange
`app.rabbitmq.exchange`, que em prod fica no default `ms-communication-exchange-dev`
(`application.yml:128-129`). Nenhum `convertAndSend` usa essa exchange (grep por `convertAndSend`:
só `EmailNotificationService.java:306`, `GuardianAuditPublisher.java:78` e `IncidentEnqueueAdapter.java:30`).
O Go não precisa portar isso.

### 2.3 HTTP de saída que outros serviços precisam aceitar

- **ms-communication** `POST http://ms-communication:8082/api/v1/messages/send`, com headers
  `X-Company-Id` e `X-Correlation-ID` (`adapters/out/feign/CommunicationMessageClient.java:10-21`).
  Só é usado no fallback, quando o RabbitMQ falha (`EmailNotificationService.java:268-291`).
  Body: `companyId, correlationId, xCorrelationId, messageType=EMAIL, recipient, templateType=ALERTA_SEGURANCA,
  subject, communicationType=EMAIL, codeUser="ADMIN_GUARDIAN", variables{serviceName, diagnosticReportHtml}`.
  O ms-communication exige `X-Company-Id` como UUID (`ms-communication/.../MessageController.java:69-72`)
  e valida `messageType`, `recipient`, `templateType` e `communicationType`.
  **Em prod `APP_GUARDIAN_TENANT_ID` está vazio, então o fallback falharia (header UUID inválido).**
- **srv-llm-gateway** `POST /api/v1/llm/complete` (`LlmGatewayClient.java:16`) e **ms-auth** client_credentials
  (`AuthTokenClient.java:19,25`). Só são usados pelo `GatewayLlmAdapter` (`infrastructure/llm/GatewayLlmAdapter.java:32-44,135-137`).
  Com `provider=none`, não são chamados em prod (§4).
- **GitHub API** (`GitHubClient.java:21-107`). Veja §4.

### 2.4 Redis

Prefixo `guardian` (`application.yml:104-109`). Chaves:
`guardian:storm:<ns>` (`RedisClusterStormStateAdapter.java:64`), `guardian:lock:<nome>` (`RedisDistributedLockAdapter.java:64`),
`guardian:idem:<chave>` (`RedisIdempotencyAdapter.java:24`), `guardian:prompt:<chave>` (`CompositePromptCatalog.java:40,110`),
`guardian:alert-cd:<escopo>` (`RedisAlertCooldownAdapter.java:27`), `guardian:rl:<bucket>...` (`RateLimiterService.java:36`).
**Nenhum outro serviço lê essas chaves.** A busca por `guardian:` fora do serviço só encontra as
authorities `guardian:read`/`guardian:write`. As chaves são internas. Mesmo assim, o Go precisa usar
os mesmos nomes e formatos, porque Java e Go vão conviver durante o corte: lock, cooldown e storm são
compartilhados.

---

## 3. Deployment real em produção

### 3.1 Deployment `ms-ai-guardian` (`kubectl get deploy ms-ai-guardian -n keepguard -o yaml`)

- Imagem `ghcr.io/keepguard/ms-ai-guardian:159d6c5`, `imagePullPolicy: IfNotPresent`, `imagePullSecrets: ghcr-secret`.
- `replicas: 1`, **`strategy: Recreate`** (evita dois watchers ou consumidores ao mesmo tempo), revisão 105.
- `serviceAccountName: ms-ai-guardian-sa`.
- Pod `ms-ai-guardian-99df48c7c-2xzh9`, Running, 0 restarts, 22 dias de idade (`kubectl get pods -l app=ms-ai-guardian`).
- O `last-applied-configuration` ainda mostra `SPRING_PROFILES_ACTIVE=local` e a imagem `1.0.0`. O spec
  vivo já foi corrigido (`kubectl set env`/`set image`), mas **o Deployment não foi gerado pelo Helm do repo** (§3.4).

### 3.2 Env (nomes; valores literais só quando não são sensíveis)

| Env | Valor / origem |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (literal) |
| `SERVER_PORT` | `8088` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/keepguard_api_db` |
| `SPRING_DATASOURCE_USERNAME` | configMap `keepguard-config` / `POSTGRES_USER` |
| `SPRING_DATASOURCE_PASSWORD` | secret `keepguard-secret` / `POSTGRES_PASSWORD` |
| `SPRING_RABBITMQ_HOST` / `_PORT` | `rabbitmq-service` / `5672` |
| `SPRING_DATA_REDIS_HOST` / `_PORT`, `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` |
| `APP_GUARDIAN_DEFAULT_RECIPIENT` | literal (e-mail pessoal do Rafael) |
| `APP_GUARDIAN_NAMESPACE` | `keepguard` |
| `APP_GUARDIAN_WATCHER_ENABLED` | `true` |
| `APP_GUARDIAN_CONSOLE_URL` | `https://app-core.keepguard.com.br` |
| `APP_GUARDIAN_LLM_PROVIDER` | **`none`** |
| `APP_GUARDIAN_OLLAMA_ENABLED` / `_OPENAI_ENABLED` / `_ANTHROPIC_ENABLED` | `false` / `false` / `false` |
| `APP_GUARDIAN_LLM_TIMEOUT_SECONDS` / `_CODEGEN_TIMEOUT_SECONDS` / `_MAX_TOKENS` | `45` / `90` / `4096` |
| `SPRING_AI_OPENAI_API_KEY` | secret `keepguard-openai` / `SPRING_AI_OPENAI_API_KEY` |
| `SPRING_AI_OPENAI_MODEL` | `gpt-4.1-mini` |
| `LLM_GATEWAY_URL` | `http://srv-llm-gateway:8650` |
| `KEEPGUARD_AUDIT_EXCHANGE` | `srv-audit-exchange-prod` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=25.0 -XX:+UseG1GC -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError` |
| `GITHUB_TOKEN` | **valor literal em texto puro no spec** (PAT do GitHub, valor omitido aqui). Veja §6 |

**Ausentes em prod**, valendo o default do `application.yml`:
- `APP_GUARDIAN_TENANT_ID` vazio (`application.yml:101`);
- `AUTH_CLIENT_SECRET_BASE` vazio (`:136`); `AUTH_BASE_URL` fica `http://ms-auth:8081` (`:134`);
- `app.rabbitmq.email-exchange` fica `srv-email-google-sender-exchange-dev` (`:130`);
- `spring.rabbitmq.username`/`password` ficam **`guest`/`guest` literais** (`application.yml:42-43`). O
  configMap `keepguard-config` traz `RABBITMQ_DEFAULT_USER=guest` e o secret `keepguard-secret` tem a chave
  `RABBITMQ_DEFAULT_PASS`. O Go deve ler os dois, como faz o ms-auth-go (§7);
- `spring.datasource` em `application-prod.yml:5-8` tem usuário e senha literais, mas o env acima sobrescreve.

### 3.3 Resources, probes e uso real

- requests `cpu 250m, memory 512Mi`; limits `cpu 1, memory 1Gi`.
- startupProbe `GET /actuator/health/liveness:8088`, initialDelay 20 s, period 10 s, failureThreshold 36, timeout 3 s.
- livenessProbe `GET /actuator/health/liveness`, period 20 s, failureThreshold 3, timeout 3 s.
- readinessProbe `GET /actuator/health/readiness`, period 10 s, failureThreshold 3, timeout 3 s.
- `kubectl top pod -l app=ms-ai-guardian`: **CPU 5m, memória 638Mi**.
- JVM (Prometheus `jvm_memory_used_bytes{application="ms-ai-guardian"}`): heap ≈ 112 MiB, non-heap ≈ 181 MiB.
  A maior parte dos 638Mi é overhead da JVM, não dado.
- Para comparar: `ms-auth-go` usa **7Mi** e CPU 1m (`kubectl top pod -l app=ms-auth-go`).

### 3.4 Helm do repo × spec vivo (divergências)

`helm/values.yaml` e `helm/templates/deployment.yaml` ainda não são a fonte da verdade:
- Helm lê `GITHUB_TOKEN` do secret `ms-ai-guardian-secret`/`GITHUB_TOKEN` (`deployment.yaml:93-97`). O
  secret existe em prod com essa chave (`kubectl get secret ms-ai-guardian-secret`, só nomes de chave),
  **mas o Deployment vivo usa o valor literal**.
- Helm define `AUTH_BASE_URL`, `AUTH_CLIENT_ID`, `AUTH_CLIENT_SECRET_BASE` (`keepguard-secret`), `APP_GUARDIAN_TENANT_ID`,
  `APP_GUARDIAN_APPROVER_*` e `RABBITMQ_HOST/PORT` (`deployment.yaml:54-86`). O vivo não tem nenhum deles.
- O vivo tem `SPRING_AI_OPENAI_*` e `APP_GUARDIAN_LLM_*_SECONDS/_MAX_TOKENS`, que não estão no Helm.
- O Helm não tem `strategy` (o default seria RollingUpdate). O vivo usa `Recreate`.
- O script de deploy só faz `kubectl set image` + `rollout status` (`script-deploy-k8s-prod.sh:72-81`).
  O Helm não é aplicado.

### 3.5 ServiceAccount e RBAC (permissões reais)

- SA `ms-ai-guardian-sa` (`kubectl get sa`).
- RoleBinding `ms-ai-guardian-rb` → Role `ms-ai-guardian-role` (namespace `keepguard`):
  - `apiGroups [""]`, resources `pods, pods/log, events, services, configmaps`, verbs **`get, list, watch, delete`**;
  - `apiGroups ["apps"]`, resources `deployments, statefulsets, replicasets`, verbs **`get, list, watch, patch, update`**.
- ClusterRoleBinding `ms-ai-guardian-nodes-reader-keepguard` → ClusterRole `ms-ai-guardian-nodes-reader`:
  `apiGroups [""]`, `nodes`, verbs `get, list, watch`.
- Igual ao repo: `helm/templates/rbac.yaml:1-55`.
- `kubectl auth can-i --as=system:serviceaccount:keepguard:ms-ai-guardian-sa -n keepguard`: get/delete pods = yes,
  get pods/log = yes, list events = yes, list nodes = yes, patch deployments = yes,
  update/patch deployments/scale = yes, list replicasets = yes, delete deployments = no.
- O Java usa estas operações (o Go precisa de cliente K8s, com client-go, e do mesmo RBAC):
  - list pods e deployments (`KubernetesInspectorService.java:39,50,121,135`);
  - list nodes (`:67`);
  - get pod e logs com `tailLines` (`:226-228,255,445`);
  - list events core/v1 (`:238`);
  - delete pod (`:390`);
  - rollout restart (`:394`; `KubernetesOpsAdapter.java:22`), que é patch de annotation;
  - rollout undo (`:398`), que lista replicasets e faz patch;
  - scale (`:402`);
  - list pods, deployments e replicasets por label `app=` (`:407-450`).
  - **Não usa `metrics.k8s.io`.**

### 3.6 Service e Ingress

- Service `ms-ai-guardian` ClusterIP `10.43.107.76`, porta `8088` → targetPort `8088`, selector `app: ms-ai-guardian`
  (`kubectl get svc ms-ai-guardian -o yaml`). Sem anotações de Prometheus.
- Ingress (`kubectl get ingress -A`): só `front-achadinhos-ingress`, `front-keepguard-core-ingress` (`/` → front)
  e `grafana-ingress`. IngressRoutes Traefik `keepguard-api-http/https` (`kubectl get ingressroute`) roteiam
  `api.keepguard.com.br` para bff-auth e bff-core (`/api/v1/core`, ...). **Nada expõe o guardian, nem o
  webhook do GitHub.** O GitHub não consegue entregar eventos, e `processed_comments` e
  `pull_request_lifecycles` estão vazias (§5.2).

### 3.7 O que o serviço faz em prod (logs e métricas)

- `kubectl logs deploy/ms-ai-guardian --tail=200`, e o arquivo inteiro retido (2266 linhas, desde
  2026-10-04 04:18Z, por causa da rotação do kubelet): **só duas mensagens se repetem**, uma vez por minuto:
  - `INFO KubernetesHealthWatcherScheduler : [AI Guardian Watcher] Namespace keepguard. Pods anômalos: 0`. O watcher está ativo;
  - `WARN KubernetesInspectorService : Não foi possível obter logs do pod keepguard/ms-company-deployment ... 404 pods "ms-company-deployment" not found`.
    Há um incidente aberto ou em reconciliação com `podName = "ms-company-deployment"` (incidente de
    deployment, não de pod). O ms-company Java foi desligado em 2026-10-03 (`scripts/deploy-all-prod.sh:68`;
    `kubectl get deploy` mostra só `ms-company-go`). O guardian tenta ler logs de um pod que não existe
    a cada varredura. **É comportamento atual. Não portar como bug a reproduzir; registrar.**
- Nenhum erro de LLM, RabbitMQ, Redis ou e-mail no período retido.
- Prometheus (`http_server_requests_seconds_count{application="ms-ai-guardian",uri!~"/actuator.*"}`), desde o start
  do pod: `GET /api/v1/guardian/incidents` = 44, `GET /incidents/{id}` = 2, `GET /alert-recipients` = 8, todos 200.
  `increase(...[7d])` = 0.
- Circuit breakers `github-api` e `llm-gateway`: `closed` (`resilience4j_circuitbreaker_state`).

---

## 4. Provider LLM, GitHub e agentes em prod

- **LLM: `APP_GUARDIAN_LLM_PROVIDER=none`** (§3.2), com `GuardianLlmProperties.java:50` (`enabled()` falso para `none`).
  O bean ativo é o `GatewayLlmAdapter`: a condição é "nem ollama nem openai"
  (`infrastructure/llm/GatewayLlmAdapter.java:24`; `SpringAiLlmAdapter.java:22`). Mas ele não fica disponível.
  `llm_invocations` = 0 linhas (§5.2). Toda investigação é heurística (`HEURISTIC_FALLBACK`).
  - O secret `keepguard-openai` está montado sem uso, e `APP_GUARDIAN_LLM_MAX_TOKENS=4096` também não tem efeito.
  - **Para o Go:** Spring AI (Ollama, OpenAI, Anthropic direto) não roda em prod. Portar só o caminho
    `gateway` (HTTP para `srv-llm-gateway` + token do ms-auth), ou nem isso nesta fase (decisão D).
- **GitHub: o token existe** (`GITHUB_TOKEN`). Mesmo assim:
  - webhook inacessível (§3.6) → `HandlePrEventUseCase`, `DeployerAgent` e o ajuste de review do `CoderAgent` nunca rodam;
  - criação de PR de hotfix: `AiDiagnosticService.java:127-140` só chama o `CoderAgent` se o
    `BusinessAnalystAgent` disser `requiresCodePr` e o serviço não tiver `deployment` nem `busybox` no nome.
    `CoderAgentService.java:44-138` cria branch, commit e PR. Ele **não depende do LLM** para tentar
    (fallback em `:188,230`). `pull_request_lifecycles` = 0, então **nunca abriu PR em prod**;
  - o circuit breaker `github-api` registra chamadas (`resilience4j_circuitbreaker_calls_seconds_count`, 6 séries),
    então houve alguma chamada de leitura ou tentativa.
- **Conclusão para o escopo do port:** o que funciona em prod é watcher + classificação heurística +
  incidentes, sugestões, ações e ciclo de vida + destinatários + e-mail e auditoria por RabbitMQ + Redis +
  6 rotas do BFF. `/diagnose`, `/diagnose/async`, a fila, o webhook, os agentes de GitHub e o LLM estão
  sem uso real (decisão D sobre portar ou não).

---

## 5. Schema, observabilidade e scripts

### 5.1 Schema `ms_ai_guardian`
- Criado ou alterado pelo **Hibernate `ddl-auto: update`** (`src/main/resources/application.yml:21-22`),
  `default_schema: ms_ai_guardian` (`:27`), entidades com `schema = "ms_ai_guardian"` (por exemplo
  `infrastructure/persistence/entity/IncidentJpaEntity.java:24`). Sem Flyway nem Liquibase (o `pom.xml` não tem).
  Nada no repo faz `CREATE SCHEMA` (grep por `create_namespaces` e `ms_ai_guardian` em yml, sql e sh). O
  schema já existia em prod: foi copiado do local por `scripts/sync-apps-data-to-prod.sh:35-45` (lista
  `APP_SCHEMAS`). A baseline do Go precisa de `CREATE SCHEMA IF NOT EXISTS`.
- `prompt_templates` e `classification_rules` existem (por exemplo `ClassificationRuleJpaEntity.java:25`), mas estão vazias (§5.2).

### 5.2 Linhas aproximadas
Via postgres exporter, sem exec: `pg_stat_user_tables_n_live_tup{schemaname="ms_ai_guardian"}` (é estimativa):

| tabela | linhas |
|---|---|
| incidents | 109 |
| incident_lifecycle_events | 843 |
| incident_action_suggestions | 535 |
| incident_evidence | 305 |
| incident_alert_deliveries | 161 |
| incident_action_executions | 0 |
| guardian_alert_recipients | 0 (estimativa; tabela minúscula pode não refletir) |
| classification_rules | 0 |
| prompt_templates | 0 |
| llm_invocations | 0 |
| processed_comments | 0 |
| pull_request_lifecycles | 0 |

### 5.3 Prometheus
- Job estático `ms-ai-guardian`, `metrics_path: /actuator/prometheus`, target `ms-ai-guardian:8088`, labels
  `application: ms-ai-guardian`, `environment: production` (`k8s/observability/prometheus-configmap.yaml:85-91`). Igual no
  ConfigMap vivo `prometheus-config` (`kubectl get cm prometheus-config`). `up{job="ms-ai-guardian"} = 1`.
- Sem ServiceMonitor (o CRD não existe no cluster) e sem anotações no pod ou no Service.
- **O Go precisa servir `/actuator/prometheus`** (a menos que se altere o job) com label `application` vindo do scrape.
- Alerta `kg_app_down` inclui `ms-ai-guardian` (`k8s/observability/alerting/rules.yaml:17`; `README.md:113`).

### 5.4 Grafana
- `k8s/observability/generate-dashboards.py:385`: `write("ms-ai-guardian.json", spring_jvm_only("ms-ai-guardian", ...))`.
  É painel só de JVM e vira inútil no Go. Trocar por `go_ms_dashboard(...)` como ms-auth e ms-company (`:353-373`).
- JSON gerado: `k8s/observability/dashboards/ms-ai-guardian.json`. Aplicação: `k8s/observability/apply-grafana-dashboards.sh:6-9`
  (ConfigMap `grafana-dashboards-json`, server-side).
- Também existe uma cópia local: `docker/grafana/provisioning/dashboards/json/ms-ai-guardian.json`.

### 5.5 Scripts
- `scripts/deploy-all-prod.sh:77`: `deploy "${CORE_ROOT}" backend/ms/ms-ai-guardian`. Trocar no corte, como em `:67-68`.
- `scripts/tunnel-prod-services-all.sh:31` (port-forward `svc/ms-ai-guardian` 8088→18088).
- `scripts/sync-apps-data-to-prod.sh:42,60` (schema e writer).
- `scripts/create-llm-oauth-clients.sh:58-60,188`: OAuth client `ms-ai-guardian` / `ROLE_SERVICE_MS_AI_GUARDIAN` para o gateway LLM.

---

## 6. Achados que vão além da migração (comportamento atual de prod)

1. **E-mails do guardian sem consumidor desde 2026-09-27.** O guardian publica em
   `srv-email-google-sender-exchange-dev` (§2.2a). O `srv-email-sender` vivo (imagem `696da00`, pod iniciado em
   2026-09-27T18:57Z) consome `srv.email.google.sender.message.send.local` / exchange `-local` (log de boot:
   `"queue":"srv.email.google.sender.message.send.local","exchange":"srv-email-google-sender-exchange-local"`).
   Causa: o `Dockerfile` do srv-email-sender só copia `application.yml` (`backend/srv/srv-email-sender/Dockerfile:27`).
   Com `APP_ENV=dev` (configMap `keepguard-config`), o `MergeInConfig` de `application-dev.yml` falha em silêncio
   (`internal/infrastructure/config/config.go:154-168`). Exporter RabbitMQ: fila `.dev` com **0 consumidores**,
   75 publicações; DLT `.dev` com 31 mensagens. O ms-communication também publica nesse exchange
   (`ms-communication/.../application-dev.yml:99-100`), então o impacto pode ser maior. **Fora do escopo do guardian, mas ele depende disso.**
2. **`GITHUB_TOKEN` em texto puro no Deployment** (`kubectl get deploy ms-ai-guardian -o yaml`; está só no
   spec, não no `last-applied`). O secret `ms-ai-guardian-secret/GITHUB_TOKEN` já existe. Recomendação:
   rotacionar o PAT e passar a usar `secretKeyRef`. No Go, só ler do secret.
3. **Sem autenticação na entrada e sem isolamento por tenant.** Qualquer pod do cluster pode chamar
   `POST /incidents/{id}/actions` (que reinicia, escala ou deleta pods) ou o webhook do GitHub (que não
   valida `X-Hub-Signature-256`: `GitHubWebhookController.java:29-32`).
4. **`APP_GUARDIAN_TENANT_ID` vazio:** `tenant_id` vazio no e-mail e fallback HTTP para o ms-communication inviável (§2.3).
5. **RabbitMQ `guest/guest` literal** (`application.yml:42-43`), sem ler o configMap ou secret (§3.2).
6. **Incidente fantasma `ms-company-deployment`** gera um WARN por minuto (§3.7).
7. **POST /actions pode ser repetido pelo BFF** em 5xx (§1.2). A idempotência precisa ser confirmada na spec.

---

## 7. Formato do Deployment Go (referência: `ms-auth-go` vivo)

`kubectl get deploy ms-auth-go -n keepguard -o yaml`:
- Env de app sem prefixo Spring: `APP_ENV=prod`, `SERVER_PORT`, `DB_HOST=postgres`, `DB_NAME=keepguard_api_db`,
  `DB_RUN_MIGRATIONS=false`. `POSTGRES_USER` vem do configMap `keepguard-config/POSTGRES_USER` e `POSTGRES_PASSWORD`
  do secret `keepguard-secret/POSTGRES_PASSWORD`. Também `REDIS_HOST=redis`.
  RabbitMQ: `RABBITMQ_HOST` e `RABBITMQ_PORT` do configMap `keepguard-config`, `RABBITMQ_USER` do configMap
  `keepguard-config/RABBITMQ_DEFAULT_USER` e `RABBITMQ_PASSWORD` do secret `keepguard-secret/RABBITMQ_DEFAULT_PASS`.
  Ainda `KEEPGUARD_AUDIT_EXCHANGE=srv-audit-exchange-prod`; `JWT_SECRET` e `AUTH_CLIENT_SECRET_BASE` vêm de `keepguard-secret`.
- `resources`: requests `memory 64Mi`, limits `memory 256Mi`, sem CPU. Uso real: 7Mi e 1m.
- Probes: liveness `GET /health` (initialDelay 5 s, period 20 s, timeout 1 s, failure 3); readiness `GET /ready`
  (initialDelay 3 s, period 10 s, timeout 1 s, failure 3). Sem startupProbe.
- `strategy: RollingUpdate` (25%/25%), `imagePullPolicy: Always`, `imagePullSecrets: ghcr-secret`.

**O que o `ms-ai-guardian-go` deve repetir e o que deve mudar**
- Repetir o formato de env do ms-auth-go: DB, Redis, RabbitMQ (do configMap e secret, nunca `guest` literal) e audit exchange.
- Específicos do guardian, com os mesmos valores de hoje:
  - `APP_GUARDIAN_NAMESPACE=keepguard`, `APP_GUARDIAN_WATCHER_ENABLED=true`;
  - `APP_GUARDIAN_DEFAULT_RECIPIENT`, `APP_GUARDIAN_CONSOLE_URL`;
  - `APP_GUARDIAN_LLM_PROVIDER=none`, `LLM_GATEWAY_URL`;
  - exchange e RK de e-mail **explícitos** (hoje é `srv-email-google-sender-exchange-dev` / `email.google.send`;
    alinhar com a correção do srv-email-sender, §6.1);
  - `GITHUB_TOKEN` do secret `ms-ai-guardian-secret`;
  - `APP_GUARDIAN_TENANT_ID` (decidir valor).
- **Manter `serviceAccountName: ms-ai-guardian-sa`** (o mesmo RBAC, §3.5) e **`strategy: Recreate`**. O watcher
  e o consumer não devem rodar em dobro. Na convivência lado a lado, deixar o watcher desligado em um dos dois
  (`APP_GUARDIAN_WATCHER_ENABLED=false` no Go até o corte).
- Probes no formato Go (`/health`, `/ready`), mais os aliases `/actuator/health/liveness` (usado pelo bff-core,
  §1.2) e `/actuator/prometheus` (scrape, §5.3).
- Memória: começar com requests 64Mi / limits 256Mi, como o ms-auth-go. O guardian Go guarda pouco
  estado em memória: logs de até 80 linhas por pod (`KubernetesInspectorService.java:226-228`) e listas de
  pods e deployments de um único namespace.
- Envs que não precisam ser portados (Spring AI sem uso): `APP_GUARDIAN_OLLAMA_ENABLED`, `_OPENAI_ENABLED`,
  `_ANTHROPIC_ENABLED`, `SPRING_AI_OPENAI_*`, `JAVA_OPTS`.
- Cuidado já conhecido: o K8s injeta `MS_AI_GUARDIAN_PORT=tcp://...` por causa do nome do Service. A config Go
  precisa ignorar isso (`docs/prompts/migrar-ms-ai-guardian-para-go.md:223-224`).
