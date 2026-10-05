# ms-ai-guardian — integrações de saída e resiliência (levantamento para migração Go)

Data: 2026-10-04. Só leitura, nenhum código alterado. Paths relativos a `keepguard-core/backend/`.

- `J/` = `ms/ms-ai-guardian/src/main/java/com/keepguard/ms_ai_guardian/`
- `R/` = `ms/ms-ai-guardian/src/main/resources/`
- `H/` = `ms/ms-ai-guardian/helm/`
- `GW/` = `srv/srv-llm-gateway/internal/`

Onde a afirmação vem de bytecode de biblioteca (Fabric8 7.3.2 no jar `target/ms-ai-guardian-1.0.52.jar`)
ou de comportamento padrão de lib, está marcado **(lib)** ou **(inferência)**.

`investbot/.../ms-analyst-finance/internal/infrastructure/resilience` **não foi lido**: a regra de
isolamento de `keepguard-core/CLAUDE.md` proíbe analisar código do investbot a partir daqui. Os padrões
de `bff-auth` e `ms-auth-go` (§6) cobrem o que precisava.

---

## 0. Achados principais

1. **A config Resilience4j do YAML não é aplicada a nada.** `R/application.yml:165-187` define
   `circuitbreaker` e `retry` com instâncias `llm-gateway` e `github-api`, mas:
   - não existe nenhuma anotação `@CircuitBreaker`/`@Retry`/`@Bulkhead`/`@RateLimiter` em `J/` (busca
     por esses nomes só acha `R/application.yml:165`);
   - o jar não contém `spring-cloud-starter-circuitbreaker-resilience4j` (lista de `BOOT-INF/lib`), e não
     há `spring.cloud.openfeign.circuitbreaker.enabled` no YAML, então o Feign não é embrulhado em breaker;
   - não há registry manual (`ofDefaults`) — o starter `resilience4j-spring-boot3` (`pom.xml:124-128`) cria
     as instâncias do YAML no registry **(inferência)**, mas ninguém chama `executeSupplier` nelas.
   - Resultado: **nenhum breaker, nenhum retry** nas chamadas HTTP. Os nomes batem com os `name` dos
     `@FeignClient` (`J/adapters/out/feign/LlmGatewayClient.java:10`, `GitHubClient.java:15`) por
     coincidência; isso não liga nada.
   - Feign sem bean `Retryer` (busca em `J/` não acha) → `Retryer.NEVER_RETRY`, padrão do Spring Cloud
     OpenFeign **(lib)**. Cliente HTTP é o `feign.Client.Default` (HttpURLConnection): o jar não tem
     `feign-hc5` nem `feign-okhttp`.
   - **Para o Go:** o comportamento atual é "timeout + try/catch". Não copiar `slidingWindowSize: 10`,
     `maxAttempts: 2` etc. como se fossem o comportamento em produção.
2. **Os únicos mecanismos de resiliência que funcionam de fato:** timeouts do Feign por cliente
   (`*ClientConfig`), timeout do `LlmContextLimiter` (`J/infrastructure/util/LlmContextLimiter.java:40-49`),
   rate limit Redis/Bucket4j (`J/infrastructure/ratelimit/RateLimiterService.java:34-51`), retry
   stateless + republicação em DLQ do listener RabbitMQ (`J/infrastructure/messaging/RabbitMqTopologyConfig.java:65-93`),
   retry interno do Fabric8 **(lib)** e fallbacks em memória quando o Redis cai (§3).
3. **Bug latente de RBAC — escala de deployment provavelmente dá 403.** A ação `SCALE_REPLAY` usa a
   subresource `/scale` (§1.7), mas a Role só dá `deployments`, sem `deployments/scale`
   (`H/templates/rbac.yaml:16-18`). Se o RBAC do Helm é o de produção, a ação falha com 403 e vira
   HTTP 502 para o usuário (`J/application/service/sre/IncidentRemediationService.java:104-111`).
4. **Falha da API do K8s pode encerrar incidente de cluster como "saudável".** `assessClusterStorm`
   engole erros e devolve listas vazias/0 (`J/adapters/out/k8s/KubernetesInspectorService.java:65-79`,
   `:119-147`) → `stormActive=false` → `IncidentReconciliationService` conta varredura saudável
   (`J/application/service/sre/IncidentReconciliationService.java:66-68`, `:75-84`). Com API fora por 3
   ciclos, o incidente de tempestade é normalizado e o e-mail "cluster normalizado" é enviado.
5. **E-mail em prod usa exchange `-dev` por padrão.** `app.rabbitmq.email-exchange` default
   `srv-email-google-sender-exchange-dev` (`R/application.yml:130`, `J/adapters/out/notification/EmailNotificationService.java:39-40`);
   `R/application-prod.yml` não sobrescreve e o Helm não injeta variável para isso
   (`H/templates/deployment.yaml:27-97`). Conferir qual exchange o `srv-email-sender` de prod consome
   (default dele também é `-dev`: `srv/srv-email-sender/internal/infrastructure/config/config.go:97-105`).
6. **Publicação RabbitMQ sem confirmação.** `RabbitTemplate` sem publisher confirms/returns
   (`J/infrastructure/config/RabbitMQConfig.java:27-32`). `publishToGoogleSender` devolve `true` assim que
   `convertAndSend` não lança (`EmailNotificationService.java:306-307`), mesmo que a exchange não exista.
7. **Cooldown de alerta é consumido antes do envio.** `AlertFanoutService` faz `tryAcquire` do cooldown e
   só depois envia (`J/application/service/sre/AlertFanoutService.java:124-130`, `:149-155`); se o envio
   falha, o próximo alerta para aquele destinatário fica bloqueado por 15 min (`R/application.yml:91`).

---

## 1. Clientes de saída

### 1.0 Visão geral

| Cliente | Destino | Timeout connect/read | Retry | Breaker | Rate limit | Se cair |
|---|---|---|---|---|---|---|
| AuthTokenClient | ms-auth `:8081` | 2 s / 5 s | não | não | não | `Optional.empty()` → segue sem Bearer |
| LlmGatewayClient | srv-llm-gateway `:8650` | 5 s / 95 s (+ corte 45/90 s) | não | não | só na fila (§1.4) | `Optional.empty()` → heurística |
| GitHubClient/GitHubApiClient | api.github.com | 5 s / 20 s | não | não | 10/s (Redis) | depende do método (§1.3) |
| CommunicationMessageClient | ms-communication `:8082` | 2 s / 8 s | não | não | não | `false` |
| Fabric8 (K8s) | API server | 8 s / 15 s | **sim (lib)** | não | não | leituras: vazio; mutações: exceção |
| Spring AI (legado) | Ollama/OpenAI | do Spring AI | **sim (lib, OpenAI)** | não | não | `Optional.empty()` |
| RabbitTemplate e-mail | `srv-email-google-sender-exchange-*` | `connection-timeout: 8000` | não | não | 20/s | fallback HTTP ou `false` |
| RabbitTemplate fila incidentes | `guardian.incident.exchange` | idem | não | não | não | exceção → HTTP 500 |
| GuardianAuditPublisher | `srv-audit-exchange-*` | idem | não | não | não | log WARN, engolido |

Timeouts Feign: `new Request.Options(connect, read, followRedirects=true)` em cada `*ClientConfig`.
As classes `*ClientConfig` não têm `@Configuration`, então valem só para o cliente que as referencia em
`configuration=` (comportamento esperado do Spring Cloud OpenFeign **(lib)**). Log Feign `BASIC` em todos.

Nenhum cliente Feign tem `RequestInterceptor` nem `ErrorDecoder` customizado (busca em `J/` vazia):
4xx/5xx viram `FeignException` padrão **(lib)**.

### 1.1 AuthTokenClient (ms-auth) — token OAuth client_credentials

- URL base `${auth.base-url:http://ms-auth:8081}` (`J/adapters/out/feign/AuthTokenClient.java:12-16`;
  `R/application.yml:133-137`; Helm `AUTH_BASE_URL=http://ms-auth:8081`, `H/templates/deployment.yaml:78-79`).
- Timeouts: connect 2 s, read 5 s (`J/adapters/out/feign/AuthTokenClientConfig.java:16-19`).
- Chamadas:
  1. `GET /api/v1/auth/oauth/runtime/secret?clientId={clientId}` com headers `X-Company-Id: {companyId}` e
     `X-Auth-Client-Secret-Base: {AUTH_CLIENT_SECRET_BASE}` (`AuthTokenClient.java:19-23`). Resposta
     `{clientId, secretEncrypted, status}` (`:30`).
  2. `POST /api/v1/auth/oauth/token` com `X-Company-Id` e corpo
     `{"grantType":"client_credentials","clientId":..., "clientSecret":...}` (`AuthTokenClient.java:25-28`;
     `J/adapters/out/feign/AuthTokenAdapter.java:60-65`). Resposta `{accessToken, expiresIn}` (`AuthTokenClient.java:32`).
  - Sem `Authorization` e sem `X-Correlation-ID`.
- Decifragem do secret: AES-256-GCM, chave = SHA-256(`secretBase` UTF-8), payload Base64 = IV (12 bytes)
  ‖ ciphertext+tag (tag 128 bits) (`J/infrastructure/oauth/OAuthSecretCrypto.java:14-15`, `:24-32`, `:40-42`).
- Cache (`AuthTokenAdapter.java`):
  - Só ativo se `auth.secret-base` preenchido (`:39-41`); senão `Optional.empty()` (`:45-47`).
  - Token por companyId em `ConcurrentHashMap`, renovado quando faltam menos de 600 s (`:32`, `:50`, `:55`);
    `expiresIn<=0` vira 3600 s (`:69`).
  - Secret decifrado por companyId em cache **sem expiração** (`:79-95`). Secret rotacionado só é
    relido após restart.
  - `synchronized(this)` global: uma renovação por vez para todas as companies (`:53`).
- Se ms-auth cai: qualquer exceção é logada WARN e devolve `Optional.empty()` (`:72-75`).
  `resolveSecret` lança `IllegalStateException` se não houver `secretEncrypted` (`:87-89`), também engolida.
- Único consumidor: `GatewayLlmAdapter.bearer` (`J/infrastructure/llm/GatewayLlmAdapter.java:134-149`).

### 1.2 LlmGatewayClient + GatewayLlmAdapter (srv-llm-gateway)

- URL `${app.guardian.llm.gateway-url:http://srv-llm-gateway:8650}` (`J/adapters/out/feign/LlmGatewayClient.java:9-13`;
  `R/application.yml:119`; local `http://localhost:8650`, `R/application-local.yml:24-27`).
- Timeouts Feign: connect 5 s, read 95 s (`J/adapters/out/feign/LlmGatewayClientConfig.java:16-19`).
- Rota: `POST /api/v1/llm/complete` (`LlmGatewayClient.java:16-21`). Headers:
  - `X-Company-Id`: `app.guardian.tenant-id`, omitido se vazio (`GatewayLlmAdapter.java:78`, `:100`);
  - `X-Correlation-ID`: `incidentId` ou UUID aleatório (`:79-81`);
  - `Authorization: Bearer <token>`: token OAuth do ms-auth se o tenantId for UUID e o token sair; senão
    `app.guardian.llm.gateway-bearer-token`; senão header ausente (`:93`, `:134-149`).
- Corpo (`J/infrastructure/llm/GatewayLlmDtos.java:12-23`, `@JsonInclude(NON_EMPTY)` → nulos e vazios
  omitidos): `providerId`, `model` (ambos opcionais, `GatewayLlmAdapter.java:83-84`), `messages:[{role:"user", content:<prompt>}]`
  (`:85`), `maxTokens` (256), `temperature` (0.2), `feature` = promptKey, `companyId`, `correlationId`,
  `sourceService:"ms-ai-guardian"` (`:27`, `:82-92`).
- Resposta lida: `content`, `model`, `providerType`, `usage{promptTokens, completionTokens, totalTokens, estimatedCostUsd, latencyMs}`,
  `ignoreUnknown` (`GatewayLlmDtos.java:27-42`).
- Timeout efetivo: a chamada roda em `CompletableFuture.supplyAsync(...).orTimeout(timeoutSeconds)`
  (`J/infrastructure/util/LlmContextLimiter.java:40-49`), com 45 s para investigação/review e 90 s para
  codegen (`R/application.yml:123-124`; usos em `J/application/service/sre/LlmInvestigationService.java:69`,
  `J/application/service/agents/ReviewerAgentService.java:104`, `J/application/service/agents/CoderAgentService.java:216`, `:237`).
  O `orTimeout` **não cancela** a chamada Feign: a thread continua até o read timeout de 95 s **(lib)**.
  O gateway tem seu próprio corte de 45 s (`LLM_TIMEOUT_SECONDS`, `GW/infrastructure/config/config.go:180`, `:229`),
  então o codegen de 90 s nunca passa de ~45 s de fato.
- Ativação: bean existe quando `provider` não é `ollama` nem `openai` (`GatewayLlmAdapter.java:24`) — ou
  seja, também para `none` e `anthropic`. `available()` = provider ≠ none e gatewayUrl preenchida
  (`:49-51`; `J/infrastructure/config/GuardianLlmProperties.java:49-51`). Helm do repo: `llmProvider: "none"`
  (`H/values.yaml:36`) → em prod o LLM está desligado, a menos que o deploy real sobrescreva.
- Se o gateway cai / dá 4xx/5xx / estoura timeout: exceção engolida em dois níveis (`GatewayLlmAdapter.java:103-106`, `:110-113`)
  → `null` → grava `LlmInvocation` com `fallbackUsed=true` (`:64-66`, `:116-132`) e devolve `Optional.empty()`;
  o chamador cai na heurística. Nunca propaga.
- Rate limit `LLM` (5/s) **não** é aplicado aqui; só no consumer da fila (§4.3). O caminho do scheduler
  (§5) chama o LLM sem rate limit.

#### Contrato do srv-llm-gateway (`POST /api/v1/llm/complete`)

- Rota com `completeAuth` (`GW/adapters/inbound/http/server.go:53`, `:60`):
  - se vier `X-Api-Key` → valida hash da chave no Postgres; inválida/revogada → 401 (`GW/adapters/inbound/http/auth.go:152-153`, `:160-193`);
  - senão exige `Authorization: Bearer <JWT HS256>` (`JWT_SECRET`, issuer `ms-auth`) com role `ADMIN`/`SYSTEM`
    ou authority `llm:write` (`auth.go:146-158`, `:35-77`, `:79-100`, `:132-141`). Sem header/inválido → 401;
    sem permissão → 403; `JWT_SECRET` vazio → 500.
  - **Pendência para o Go do guardian:** conferir se o token client_credentials do `ms-ai-guardian` tem
    `SYSTEM` ou `llm:write`; senão toda chamada dá 403 (que hoje vira heurística silenciosa).
- Request (`GW/application/dto/dto.go:35-45`): mesmos campos do Java. `correlationId`/`companyId` vazios são
  preenchidos com `X-Correlation-ID`/`X-Company-Id`; `X-Tenant-Id` opcional (`GW/adapters/inbound/http/handlers/handlers.go:140-152`).
- Validação: `messages` vazio ou modelo não resolvido → 400 `INVALID_INPUT` (`GW/application/complete/usecase.go:76-85`);
  provider por id/modelo/default (`usecase.go:170-231`).
- Response 200 (`dto.go:110-116`): `{content, usage{...UsageViewDTO}, model, providerType, requestId}`.
- Erros (`handlers.go:314-331`), corpo `{"error": CODE, "message": ...}`:
  400 `INVALID_BODY`/`INVALID_INPUT` (inclui sem provider e sem API key), 404 `NOT_FOUND` (providerId inexistente),
  502 `PROVIDER_DOWN`, 500 `INTERNAL_ERROR`.

### 1.3 GitHubClient + GitHubApiClient (API do GitHub)

- URL `${app.github.api-url:https://api.github.com}` (`J/adapters/out/feign/GitHubClient.java:14-18`).
- Timeouts: connect 5 s, read 20 s (`J/adapters/out/feign/GitHubClientConfig.java:16-19`).
- Headers em toda chamada: `Authorization: Bearer ${GITHUB_TOKEN}` e `Accept: application/vnd.github+json`
  (`J/adapters/out/github/GitHubApiClient.java:27`, `:39-47`). Sem `X-GitHub-Api-Version`, sem correlation.
  Owner `app.github.owner` = `keepguard` (`:36-37`; `R/application.yml:84-86`). Token do secret
  `ms-ai-guardian-secret/GITHUB_TOKEN` (`H/templates/deployment.yaml:93-97`).
- Antes de cada chamada: `acquireGitHubPermit()` (10/s, §3.1) e, se o token estiver vazio,
  `IllegalStateException` (`GitHubApiClient.java:40-45`) — que cai no `catch` do método.
- `{path}` em `getFileContent`/`putFileContent` vai com `/` sem escape (o `SpringMvcContract` mantém
  barras em `@PathVariable` **(lib)**). No Go: escapar por segmento, não o path inteiro.

| Método Java | HTTP | Corpo | Em erro |
|---|---|---|---|
| `getBranchSha` (`:50-59`) | `GET /repos/{o}/{r}/git/ref/heads/{branch}` (`GitHubClient.java:21-27`) | — | **lança** `RuntimeException` |
| `createBranch` (`:62-76`) | `POST /repos/{o}/{r}/git/refs` (`GitHubClient.java:29-35`) | `{ref:"refs/heads/X", sha}` | `false` |
| `listSourceFilePaths` (`:79-102`) | `getBranchSha` + `GET /repos/{o}/{r}/git/trees/{sha}?recursive=1` (`GitHubClient.java:37-44`) | — | `List.of()` |
| `getFileContent` (`:115-127`) | `GET /repos/{o}/{r}/contents/{path}?ref={branch}` (`GitHubClient.java:46-53`) | — | `Map.of()` (inclui 404) |
| `commitFileChange` (`:130-147`) | `PUT /repos/{o}/{r}/contents/{path}` (`GitHubClient.java:55-62`) | `{message, content(base64), branch, sha?}` | `false` |
| `createPullRequest` (`:150-167`) | `POST /repos/{o}/{r}/pulls` (`GitHubClient.java:64-70`) | `{title, body, head, base}` | **lança** `RuntimeException` |
| `submitReview` (`:170-185`) | `POST /repos/{o}/{r}/pulls/{n}/reviews` (`GitHubClient.java:72-79`) | `{body, event}` | `false` |
| `addComment` (`:188-198`) | `POST /repos/{o}/{r}/issues/{n}/comments` (`GitHubClient.java:81-88`) | `{body}` | `false` |
| `replyToPrReviewComment` (`:201-216`) | `POST /repos/{o}/{r}/pulls/{n}/comments` (`GitHubClient.java:90-97`) | `{body, in_reply_to:<long>}` | fallback `addComment` |
| `getPrReviewComments` (`:219-239`) | `GET /repos/{o}/{r}/pulls/{n}/comments` (`GitHubClient.java:99-105`) | — | lista vazia |
| `getPullRequestStatus` (`:242-254`) | `GET /repos/{o}/{r}/pulls/{n}` (`GitHubClient.java:107-113`) | — | `{merged:false, state:"open", mergedBy:""}` |

- As exceções de `getBranchSha`/`createPullRequest` são capturadas no pipeline de hotfix
  (`J/application/service/AiDiagnosticService.java:134-139`).
- Sem paginação em `listReviewComments` (só a 1ª página, 30 itens, padrão do GitHub).

### 1.4 CommunicationMessageClient (ms-communication) — fallback HTTP de e-mail

- URL `${app.communication.url:http://ms-communication:8082}` (`J/adapters/out/feign/CommunicationMessageClient.java:10-14`); a
  propriedade não existe no YAML nem no Helm → sempre o default.
- Timeouts: connect 2 s, read 8 s (`J/adapters/out/feign/CommunicationMessageClientConfig.java:16-19`).
- `POST /api/v1/messages/send`, headers `X-Company-Id: {tenantId}` e `X-Correlation-ID: {cid}`
  (`CommunicationMessageClient.java:17-21`). Sem `Authorization`.
- Corpo (`J/adapters/out/notification/EmailNotificationService.java:271-283`): `companyId`, `correlationId`,
  `xCorrelationId`, `messageType:"EMAIL"`, `recipient` (destinatário padrão), `templateType:"ALERTA_SEGURANCA"`,
  `subject`, `communicationType:"EMAIL"`, `codeUser:"ADMIN_GUARDIAN"`, `variables{serviceName, diagnosticReportHtml}`.
- Adapter sem try (`J/adapters/out/feign/CommunicationMessageAdapter.java:16-18`); o chamador captura e
  devolve `false` (`EmailNotificationService.java:284-290`).
- Só é usado no caminho `send()` → `dispatchEmail` quando a publicação RabbitMQ falhou
  (`EmailNotificationService.java:248-266`). O caminho de alertas por destinatário (`sendHtmlTo`, usado pelo
  fan-out) **não** tem fallback HTTP (`:53-69`).

### 1.5 EmailNotificationService (RabbitMQ → srv-email-sender)

- Exchange `app.rabbitmq.email-exchange` (default `srv-email-google-sender-exchange-dev`), routing key
  `email.google.send` (`EmailNotificationService.java:39-43`; `R/application.yml:130-131`). A exchange **não** é
  declarada pelo guardian.
- Antes de publicar: `acquireEmailPermit()` (20/s) (`:296`).
- Payload `HashMap` serializado pelo `Jackson2JsonMessageConverter` (`:298-306`):
  `{tenant_id, x_correlation_id, correlationId, to, subject, html}`. Propriedades AMQP **(lib)**:
  `content_type: application/json`, `content_encoding: UTF-8`, header `__TypeId__: java.util.HashMap`,
  `delivery_mode: 2`. Sem header `X-Correlation-ID`.
- `correlationId` estável: incidentId, ou UUID v3 de `serviceName|logContext|subject[|recipient]`
  (`:55-58`, `:250-253`).
- Se o RabbitMQ cai: `convertAndSend` lança → `false` (`:308-311`). Em `send()` tenta HTTP (§1.4); em
  `sendHtmlTo` só devolve `false`. Em ambos publica auditoria `GUARDIAN_ALERT_FAILED` (`:60-67`, `:256-261`).

### 1.6 GuardianAuditPublisher (RabbitMQ → srv-audit)

- Exchange `keepguard.audit.exchange` (default `srv-audit-exchange-dev`; prod `srv-audit-exchange-prod` via
  `R/application-prod.yml:22-24` e `H/templates/deployment.yaml:58-59`), routing key `audit.event`
  (`J/adapters/out/audit/GuardianAuditPublisher.java:27-34`; `R/application.yml:139-144`).
- Declara a exchange **ativa** uma vez: `exchangeDeclare(exchange, "topic", durable=true)` (`:89-98`). No Go,
  declarar com os mesmos parâmetros (topic, durable, não auto-delete) para não dar 406.
- Evento (`:48-74`): `eventId`, `occurredAt` (ISO-8601), `schemaVersion:1`, `sourceService:"ms-ai-guardian"`,
  `correlationId` (gera UUID se vazio, `:45-47`), `tenantId`/`companyId` do MDC se houver (`:54-61`),
  `action`, `outcome`, `actor{type, codeUser?}` (`:64-73`), `resource{type, id}`.
- Header `X-Correlation-ID: {cid}` e `deliveryMode PERSISTENT` (`:78-82`); `__TypeId__: java.util.HashMap` **(lib)**.
- Fire-and-forget em `CompletableFuture.runAsync`; erro logado WARN e engolido (`:75-86`). `keepguard.audit.enabled`
  desliga tudo (`:27-28`, `:42-44`).

### 1.7 KubernetesInspectorService / KubernetesOpsAdapter (Fabric8 → API do Kubernetes)

Cliente: `KubernetesClient` `@Lazy` (`J/infrastructure/config/KubernetesClientConfig.java:29-31`; injeção `@Lazy`
em `J/adapters/out/k8s/KubernetesInspectorService.java:33-35` e `J/adapters/out/k8s/KubernetesOpsAdapter.java:15-17`).
HTTP subjacente: Vert.x (`kubernetes-httpclient-vertx-7.3.2.jar` no jar).

Timeouts: connect 8 s, request 15 s, scale 15 s (`KubernetesClientConfig.java:19-20`, `:56-63`).
Retry da lib: `StandardHttpClient.shouldRetry` refaz em erro de I/O e em HTTP **429** e **≥500**, com backoff
exponencial **(lib, bytecode)**; limites padrão do Fabric8 (10 tentativas, 100 ms inicial) **(lib, não
configurado no projeto)**. Vale para qualquer verbo, inclusive PATCH/DELETE.

Chamadas exatas (namespace = `app.guardian.namespace`, `keepguard`, `R/application.yml:88`):

| Uso Java | REST equivalente | Parâmetros / filtro |
|---|---|---|
| `listUnhealthyPods` (`KubernetesInspectorService.java:37-46`) | `GET /api/v1/namespaces/{ns}/pods` | filtro no cliente `isPodUnhealthy` (`:163-211`) |
| `listUnhealthyPodsForService` (`:405-412`), `resolvePod` (`:450`) | `GET /api/v1/namespaces/{ns}/pods?labelSelector=app%3D{svc}` | `resolvePod` pega o 1º item |
| `resolvePod` (`:444-448`), `describePodHealth` (`:255`) | `GET /api/v1/namespaces/{ns}/pods/{name}` | 404 → `null` **(lib)** |
| `getPodLogs` (`:224-234`) | `GET /api/v1/namespaces/{ns}/pods/{name}/log?tailLines=80` | **sem** `container`, **sem** `previous`; depois corta para os últimos 4000 chars (`:229`) |
| `getRecentWarningEvents` (`:236-251`) | `GET /api/v1/namespaces/{ns}/events` (todos) | filtro no cliente: `type==Warning` e `involvedObject.name==podName`; formata `[lastTimestamp] reason: message (Count: n)` |
| `isAnyNodeNotReady` (`:65-79`) | `GET /api/v1/nodes` (cluster-scope) | alguma condição `Ready` ≠ `True` |
| `listDeploymentsWithZeroReplicas` (`:48-63`), `countActiveDeployments` (`:119-131`), `listUnavailableDeployments` (`:133-147`) | `GET /apis/apps/v1/namespaces/{ns}/deployments` | filtros por `spec.replicas` e `status.availableReplicas` |
| `findDeployment` (`:414-427`) | `GET /apis/apps/v1/namespaces/{ns}/deployments/{svc}`; se 404, `GET .../deployments?labelSelector=app%3D{svc}` | 1º item |
| `countReplicaSets` (`:429-440`) | `GET /apis/apps/v1/namespaces/{ns}/replicasets` (todos) | conta RS com `ownerReferences[].name == deploymentName` |
| `deletePod` (`:389-391`) — ação `RECREATE_POD` | `DELETE /api/v1/namespaces/{ns}/pods/{name}` | sem grace period explícito; 404 não lança **(inferência, lib)** |
| `rolloutRestart` (`:393-395`; `KubernetesOpsAdapter.java:20-23`) | `PATCH /apis/apps/v1/namespaces/{ns}/deployments/{name}`, `Content-Type: application/json-patch+json` | corpo `[{"op":"add","path":"/spec/template/metadata/annotations/kubectl.kubernetes.io~1restartedAt","value":"<agora UTC ISO>"}]` **(lib, bytecode `RollingUpdater.restart`)** |
| `rollbackRevision` (`:397-399`) — ação `ROLLBACK_REVISION` | `GET .../deployments/{name}` → `GET /apis/apps/v1/namespaces/{ns}/replicasets?labelSelector=<spec.selector.matchLabels>` → `PATCH .../deployments/{name}` (JSON Patch por diff) | ordena RS por anotação `deployment.kubernetes.io/revision` decrescente; copia `rs[1].spec.template` para o deployment e põe a anotação de revisão = `rs[0]` **(lib, bytecode `DeploymentOperationsImpl.undo`)**. Com menos de 2 RS → `IndexOutOfBounds`. Não remove o label `pod-template-hash` do template copiado (string ausente do bytecode) — o `kubectl rollout undo` remove |
| `scaleDeployment` (`:401-403`) — ação `SCALE_REPLAY` | `GET /apis/apps/v1/namespaces/{ns}/deployments/{name}/scale` → `PUT .../scale` com `spec.replicas=N` e `resourceVersion` nulo | **(lib, bytecode `HasMetadataOperation.scale(int)`)**; não espera rollout. `SCALE_REPLAY` chama 0 e depois N em seguida, sem espera (`J/application/service/sre/IncidentRemediationService.java:134-137`) |

Observações para o port:
- `isPodUnhealthy` lê **logs de todo pod Running de aplicação** a cada chamada (`:200-208`), exceto infra listada
  em `SKIP_LOG_SCAN_APPS` (`:26-29`) e o próprio guardian (`:213-222`). Isso é 1 chamada `/log` por pod por ciclo,
  e de novo dentro de `collectFacts` → `listUnhealthyPodsForService` (`:348`, `:405-412`).
- `collectFacts` (`:288-378`) faz, por incidente: deployment get(/list), replicasets list, pod get/list, events list
  do namespace inteiro, pod get (describe), log, pods list por label + logs. Chamado no diagnóstico
  (`J/application/service/AiDiagnosticService.java:52`), na reconciliação de cada incidente aberto
  (`IncidentReconciliationService.java:70-71`, `:97-98`) e antes/depois de remediação (`IncidentRemediationService.java:66-67`, `:90-91`).
- Em erro, as leituras **não lançam** (§2.3). As mutações **lançam**.
- RBAC (`H/templates/rbac.yaml`): Role com `pods, pods/log, events, services, configmaps` get/list/watch/delete
  (`:13-15`) e `deployments, statefulsets, replicasets` get/list/watch/patch/update (`:16-18`); ClusterRole de
  `nodes` get/list/watch (`:34-54`). **Falta `deployments/scale`** (achado 3). ServiceAccount `ms-ai-guardian-sa`
  (`:1-5`; `H/templates/deployment.yaml:18`).

### 1.8 SpringAiLlmAdapter (legado Ollama/OpenAI)

- Bean só com `provider` = `ollama` ou `openai` (`J/infrastructure/llm/SpringAiLlmAdapter.java:22`).
- Chama `ChatClient.prompt(prompt).call().content()` com corte `callWithTimeout` (`:45-48`;
  `LlmContextLimiter.java:29-38`). Config Spring AI em `R/application.yml:45-79` (Ollama `pull-model-strategy: never`, `max-retries: 0` só no init).
- Retry: o cliente OpenAI do Spring AI usa o `RetryTemplate` padrão `spring.ai.retry.*` (não configurado aqui)
  **(lib, inferência)**; como o `orTimeout` não cancela, esse retry segue em background.
- Erro/timeout → `Optional.empty()` + `LlmInvocation` com fallback (`:50-60`).
- **Para o Go:** não portar. O caminho oficial é o gateway (`R/application.yml:46-47`, `pom.xml:209-214`).

### 1.9 IncidentEnqueueAdapter (publica na fila de incidentes)

- `convertAndSend("guardian.incident.exchange", "guardian.incident.process", IncidentQueueMessage)`
  (`J/infrastructure/messaging/IncidentEnqueueAdapter.java:30-33`). Mensagem em §4.4.
- Origem: `POST /api/v1/guardian/diagnose/async` (`J/adapters/in/rest/diagnostic/DiagnosticController.java:26-31`;
  `J/application/service/DiagnosticUseCaseService.java:28-31`). O scheduler **não** usa a fila: chama
  `diagnosePod` direto (`J/adapters/in/scheduler/KubernetesHealthWatcherScheduler.java:74`, `:81-82`).
- Sem try: RabbitMQ fora → exceção sobe para o controller (HTTP 500 pelo handler global, **inferência**).

---

## 2. O que acontece quando cada dependência cai

### 2.1 HTTP
- **ms-auth:** sem token → `Authorization` cai no bearer estático ou some (`GatewayLlmAdapter.java:134-149`) → gateway
  responde 401 → heurística. Nada propaga.
- **srv-llm-gateway:** heurística; `LlmInvocation.fallbackUsed=true` (§1.2).
- **GitHub:** ver coluna "Em erro" de §1.3. No scan de PRs (`J/application/service/pr/HandlePrEventUseCase.java:71-90`),
  status vira "open/não mergeado" e comentários vazios — o ciclo segue silencioso.
- **ms-communication:** `false`, delivery não muda de status (só é fallback).

### 2.2 RabbitMQ
- E-mail: `false` → `IncidentAlertDelivery` com `FAILED` (`AlertFanoutService.java:131-137`); `notificationSent`
  continua `false`, mas o cooldown já foi consumido (achado 7).
- Auditoria: perdida, só log.
- Fila de incidentes: publicação lança (HTTP 500); consumer reconecta sozinho (container Spring **(lib)**).

### 2.3 Kubernetes
- Leituras engolem erro e devolvem neutro: pods/deployments → lista vazia (`KubernetesInspectorService.java:42-45`, `:59-62`, `:143-146`),
  contagem → 0 (`:127-130`), nós → `false` (`:75-78`), logs → string `"Logs não disponíveis: <erro>"` (`:230-233`) que
  entra no snippet do incidente e no fingerprint (`AiDiagnosticService.java:53`, `:56`), eventos → vazio (`:247-250`),
  describe → `"Erro ao inspecionar pod: ..."` (`:283-285`), deployment → `null` ⇒ `NO_CONTROLLER` (`:423-426`, `:460-462`),
  RS → 0, pod → `null`.
- Efeitos: scheduler vê "0 pods anômalos" e não abre nada; incidente de serviço fica "não saudável" (desired 1 /
  available 0, `:291-293`, `:349-353`); incidente de cluster pode ser **normalizado indevidamente** (achado 4).
- Mutações lançam: remediação manual → audit `GUARDIAN_REMEDIATION_FAILED` + HTTP 502 (`IncidentRemediationService.java:104-111`);
  deploy após merge → log e `false` (`J/application/service/agents/DeployerAgentService.java:56-58`).
- Falha ao criar o cliente: `@Lazy`, então só aparece na 1ª chamada; há fallback `Config.autoConfigure` (`KubernetesClientConfig.java:48-53`).

### 2.4 Redis
Todos os adapters caem para memória local (§3). Com Redis "pendurado" (não recusando), cada operação espera até o
`timeout: 3000ms` do Lettuce (`R/application.yml:32`) antes do fallback. Health do Redis desligado
(`R/application.yml:156-157`).

---

## 3. Rate limit e adapters Redis (algoritmos para portar)

Prefixo de chave: `app.guardian.redis.key-prefix` = `guardian` (`R/application.yml:104-105`;
`J/infrastructure/config/GuardianProperties.java:29-35`). Conexão: `SPRING_DATA_REDIS_HOST/PORT` (`redis:6379`),
pool Lettuce max-active 8 (`R/application.yml:29-38`; `H/templates/deployment.yaml:50-53`). Nenhum script Lua
exceto o release do lock.

### 3.1 RateLimiterService (Redis janela fixa + Bucket4j local)
- Buckets e limites: `GITHUB` 10/s, `LLM` 5/s, `EMAIL` 20/s (`R/application.yml:110-113`;
  `J/application/port/out/cache/RateLimiterPort.java:5-21`).
- Algoritmo Redis (`J/infrastructure/ratelimit/RateLimiterService.java:34-51`), **bloqueante**:
  1. `key = guardian:rl:{bucket minúsculo}:{epochSecond}`;
  2. `n = INCR key`; se `n == 1`, `EXPIRE key 2` (dois comandos, não atômico);
  3. se `n > limite`: `sleep 200 ms` (`:72-78`) e **recursão** em `acquire` (nova janela ou nova tentativa).
- Fallback (qualquer exceção do Redis): Bucket4j local por bucket, capacidade `max(2×limite, 4)`, refill guloso de
  `limite` tokens por segundo, `consumeUninterruptibly(1)` (bloqueia) (`:47-50`, `:62-70`).
- Usos: GitHub em toda chamada (`GitHubApiClient.java:40-42`), e-mail antes de publicar (`EmailNotificationService.java:296`),
  LLM só no consumer da fila (`J/adapters/in/messaging/IncidentQueueConsumer.java:28`).
- **Go:** loop em vez de recursão; `INCR`+`EXPIRE` num pipeline `MULTI` ou Lua (`if INCR==1 then EXPIRE`); respeitar
  `ctx`; fallback `golang.org/x/time/rate.NewLimiter(rate.Limit(n), max(2n,4))` com `Wait(ctx)`.

### 3.2 RedisDistributedLockAdapter (lock de rollout)
- `tryAcquire`: `SET guardian:lock:{name} {ownerId} NX EX {ttl}` (`J/infrastructure/redis/RedisDistributedLockAdapter.java:34-44`, `:63-65`).
- `release`: Lua `if GET==owner then DEL` (`:20-27`, `:52-56`).
- Fallback: `ConcurrentHashMap.putIfAbsent(name, owner)` **sem TTL** (`:45-48`); release remove a entrada local sempre (`:57-60`).
- Usos: `deploy:{serviceName}` com owner `remediation_{incidentId}_{ms}` e TTL 600 s (`IncidentRemediationService.java:84-87`, `:112-114`;
  `R/application.yml:106`); `deploy:{repoName}` com owner `deploy_pr_{n}_{ms}` (`DeployerAgentService.java:41-46`, `:59-61`).
  Não há renovação do lock.

### 3.3 RedisIdempotencyAdapter (webhook GitHub)
- `SET guardian:idem:{key} "1" NX EX {ttl}` → `true` se gravou (`J/infrastructure/redis/RedisIdempotencyAdapter.java:23-28`).
- Chave `gh:{X-GitHub-Delivery}`, TTL 86400 s (`HandlePrEventUseCase.java:92-98`; `R/application.yml:107`). Delivery vazio → sempre processa.
- Fallback: mapa local sem TTL (cresce para sempre) (`:29-32`). Não é removida em erro de processamento (sem "undo").

### 3.4 RedisAlertCooldownAdapter (anti-flapping de e-mail)
- `SET guardian:alert-cd:{scope} "1" NX EX {cooldownMin×60}`; escopo vazio ou cooldown ≤ 0 → libera
  (`J/infrastructure/redis/RedisAlertCooldownAdapter.java:23-31`). Cooldown 15 min (`R/application.yml:91`).
- Escopos: `storm:opened:{incidentId}:{email}` / `storm:normalized:{incidentId}:{email}` (`AlertFanoutService.java:100-101`, `:114-115`, `:124`)
  e `{kind minúsculo}:{serviceName}:{email}` (`:149`).
- Fallback: timestamp local por escopo, janela deslizante simples (`:32-41`).

### 3.5 RedisClusterStormStateAdapter (estado de tempestade)
- `GET guardian:storm:{namespace}` → JSON; `SET ... EX max(ttl, 60)` com ttl 7200 s; `DEL` no clear
  (`J/infrastructure/redis/RedisClusterStormStateAdapter.java:27-65`; `R/application.yml:94-98`).
- JSON (Jackson pelos getters de `J/application/dto/ClusterStormState.java:7-62`):
  `{"namespace","incidentId","startedAtEpochMs","confirmStreak","nodeNotReady","affectedServices"}`. **Manter os
  nomes no Go** — o estado vivo no Redis atravessa o corte Java→Go.
- Fallback: mapa local, sempre escrito antes do Redis (`:43`); leitura usa local só se o Redis lançar (`:35-38`).
- Uso: `J/application/service/sre/ClusterStormService.java:46-61`, `:72-124`.

### 3.6 Cache de prompt (extra, mesmo Redis)
- `GET/SET guardian:prompt:{key}` valor `"{version}\n{body}"`, TTL 300 s; `DEL` quando o seed atualiza
  (`J/infrastructure/prompt/CompositePromptCatalog.java:39-66`, `:108-113`; `R/application.yml:108`).
- `llm-cache-ttl-seconds` (`R/application.yml:109`) não é usado em lugar nenhum.

---

## 4. RabbitMQ

Conexão: `rabbitmq-service:5672`, `connection-timeout: 8000` (`R/application.yml:39-44`; `R/application-prod.yml:9-11`;
`H/templates/deployment.yaml:46-49`). Usuário/senha em prod: não definidos no `application-prod.yml` nem no Helm →
default do Spring `guest/guest` **(inferência)**.

### 4.1 Topologia declarada (RabbitAdmin declara os `@Bean` na conexão **(lib)**)
- `guardian.incident.exchange`: topic, durable, não auto-delete (`J/infrastructure/messaging/RabbitMqTopologyConfig.java:12`, `:20-23`).
- `guardian.incident.process.queue`: durable, args **`x-dead-letter-exchange=guardian.incident.exchange`**,
  **`x-dead-letter-routing-key=guardian.incident.process.dlq.rk`** (`:14`, `:25-31`). Sem TTL, sem max-length.
- `guardian.incident.process.dlq`: durable, sem args, **sem consumer** (`:17`, `:33-37`).
- Bindings: fila ← exchange com `guardian.incident.process` (`:15`, `:39-42`); DLQ ← exchange com
  `guardian.incident.process.dlq.rk` (`:18`, `:44-47`).
- `ms-communication-exchange-dev`: topic durable (`J/infrastructure/config/RabbitMQConfig.java:14-20`; `R/application.yml:128`).
  Ninguém publica nela no guardian — declaração morta.
- **Go:** declarar a fila com **exatamente** esses dois args; qualquer diferença dá `406 PRECONDITION_FAILED` na fila
  que já existe em prod.

### 4.2 Container do listener (`rabbitListenerContainerFactory`, `RabbitMqTopologyConfig.java:65-93`)
- `SimpleRabbitListenerContainerFactory` criado à mão → as propriedades `spring.rabbitmq.listener.simple.*` do Boot
  não se aplicam (nenhuma está configurada mesmo). Valores efetivos = padrão do Spring AMQP **(lib)**:
  concurrency 1, prefetch 250, ack mode `AUTO`, `defaultRequeueRejected=true`.
- Conversor: `Jackson2JsonMessageConverter` (há dois beans iguais: `jsonMessageConverter` em `RabbitMqTopologyConfig.java:49-52` e
  `messageConverter` em `RabbitMQConfig.java:22-25`; o parâmetro `messageConverter` resolve pelo nome).
- Retry **stateless** (em memória, sem requeue): `SimpleRetryPolicy(3)` = 3 tentativas no total, backoff exponencial
  1 s → 2 s (mult 2, máx 5 s) (`:76-84`, `:86-91`).
- Esgotou: `RepublishMessageRecoverer` publica a mensagem em `guardian.incident.exchange` com routing key
  `guardian.incident.process.dlq.rk` (→ DLQ) e a original recebe **ack** (`:54-63`). O recoverer adiciona os headers
  `x-exception-message`, `x-exception-stacktrace`, `x-original-exchange`, `x-original-routingKey` **(lib)**.
  O DLX da fila (§4.1) só entraria em reject sem requeue, que esse arranjo praticamente não produz.
- Erro de conversão de JSON também passa pelo retry e acaba na DLQ (a conversão roda dentro da cadeia de advice) **(inferência, lib)**.
- Com `spring.threads.virtual.enabled=true` (`R/application.yml:5-7`) o Boot só troca o executor do container que ele
  mesmo configura; este é manual **(inferência)**.

### 4.3 IncidentQueueConsumer
- `@RabbitListener(queues = "guardian.incident.process.queue")` (`J/adapters/in/messaging/IncidentQueueConsumer.java:20-21`).
- Por mensagem: loga latência na fila (`now - enqueuedTimestamp`) (`:22-24`), `acquireAiPromptPermit()` (5/s, bloqueante)
  (`:28`), `diagnosePod(...)` (`:30-36`). Exceção → log e **rethrow** (`:40-43`) → retry → DLQ.
- **Idempotência: nenhuma** por `trackingId`. A única deduplicação é a do domínio: incidente aberto com o mesmo
  fingerprint é atualizado e, se já notificado e `forceSendEmail=false`, retorna cedo (`AiDiagnosticService.java:58-80`).
  Um retry após falha parcial repete contadores, eventos de ciclo de vida e pode reenviar alerta.
- O que de fato vai para a DLQ: falhas que propagam de `diagnosePod` — basicamente Postgres (saves em
  `AiDiagnosticService.java:75`, `:88`, `:100`) e JSON inválido. K8s, LLM e e-mail são engolidos antes.

### 4.4 Formato de `IncidentQueueMessage`
- Classe (`J/infrastructure/messaging/dto/IncidentQueueMessage.java:11-23`), JSON:
  `{"trackingId":"<uuid>","namespace":"...","podName":"...","serviceName":"...","errorReason":"...","forceSendEmail":false,"enqueuedTimestamp":<epoch ms>}`.
- Propriedades AMQP **(lib)**: `content_type: application/json`, `content_encoding: UTF-8`,
  header `__TypeId__: com.keepguard.ms_ai_guardian.infrastructure.messaging.dto.IncidentQueueMessage`,
  `delivery_mode: 2`. **Sem** `correlation_id`, `message_id` ou `X-Correlation-ID`.
- No consumo Java o tipo vem do parâmetro do método (precedência INFERRED) **(lib)**; o `__TypeId__` é ignorado.
  **Go:** ignorar `__TypeId__` e desserializar JSON puro; publicar sem ele (mensagens Java ainda na fila no corte
  são JSON compatível).

---

## 5. Scheduler `KubernetesHealthWatcherScheduler`

- Dois `@Scheduled` (`@EnableScheduling` em `J/MsAiGuardianApplication.java:12`):
  1. `scanPullRequestInteractions`: `fixedDelay = 45000`, `initialDelay = 10000` (`J/adapters/in/scheduler/KubernetesHealthWatcherScheduler.java:29-36`)
     → `HandlePrEventUseCase.scanOpenPullRequests` (`HandlePrEventUseCase.java:71-90`): para cada PR ativo no banco,
     `GET pulls/{n}`; se mergeado e ainda não marcado → deploy (`rolloutRestart` com lock); senão lista comentários e
     processa os novos (dedupe por `ProcessedComment` no Postgres, `:54-62`).
  2. `scanClusterHealth`: `fixedDelayString = ${app.guardian.scan-interval-ms:60000}`, `initialDelay = 30000` (`:38`);
     desliga com `app.guardian.watcher-enabled=false` (`:40-42`; Helm `APP_GUARDIAN_WATCHER_ENABLED`, `H/templates/deployment.yaml:72-73`).
- **Sem lock distribuído** em nenhum dos dois. Proteção é só `replicas: 1` (`H/templates/deployment.yaml:9`). Durante
  rollout (estratégia padrão RollingUpdate, maxSurge 25% → 1 pod extra **(inferência)**) dois pods rodam juntos por
  alguns segundos.
- Executor: com threads virtuais ligadas, o Boot usa `SimpleAsyncTaskScheduler` → as duas tarefas podem rodar em
  paralelo, cada uma sem sobrepor a si mesma (fixedDelay) **(lib, inferência)**.
- Ciclo de `scanClusterHealth` (`:43-88`):
  1. `ClusterStormService.handleWatcherScan(ns)` (`ClusterStormService.java:46-61`): `assessClusterStorm` (nodes + 2× deployments list).
     Tempestade = algum nó NotReady **ou** (≥5 deployments indisponíveis **e** ≥40% dos ativos) (`KubernetesInspectorService.java:81-117`;
     `R/application.yml:94-98`). Se ativa: incrementa `confirmStreak` no Redis; alerta imediato se nó NotReady, senão após 2 ciclos;
     abre/atualiza incidente de cluster e faz fan-out com cooldown (`ClusterStormService.java:72-124`); depois só reconcilia e sai (`:45-50`).
  2. Senão: `listUnhealthyPods` → para cada pod: serviço = label `app` ou nome do pod (`:55-58`); motivo = `phase`, ou
     `CODE_DEFECT_*`/`PANIC_RUNTIME_EXCEPTION`/`NullPointerException` achados em 80 linhas de log (`:60-73`);
     `diagnosePod(..., forceSendEmail=false)` **síncrono** (`:74`).
  3. `listDeploymentsWithZeroReplicas` → `diagnosePod(ns, "{dep}-deployment", dep, "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE", false)` (`:77-83`).
  4. `incidentReconciliationService.reconcileOpenIncidents()` (`:85`): para cada incidente aberto, avalia saúde; 3
     varreduras saudáveis seguidas → `NORMALIZED` + e-mail (`IncidentReconciliationService.java:52-137`; `R/application.yml:92`).
  - Qualquer exceção do ciclo é logada e o ciclo termina (`:86-88`); o próximo roda normalmente.
- **Go:** um `time.Ticker`-like com "delay após término" (não ticker fixo) por tarefa; se for rodar >1 réplica,
  `SET guardian:lock:scan:{tarefa} {pod} NX EX {intervalo×2}` por ciclo, liberado pelo Lua do §3.2. Montar um
  snapshot do namespace por ciclo (pods, deployments, RS, events, nodes) e reaproveitar em `collectFacts`, em vez de
  repetir as listas por incidente.

---

## 6. Padrão Go recomendado

### 6.1 Referências no monorepo
- **ms-auth-go `resilient`** (`ms/ms-auth-go/internal/adapters/out/http/resilient/resilient_client.go`): `net/http` +
  `sony/gobreaker/v2` por destino; timeout por tentativa; retry **só em GET** (`:101-125`); breaker conta só erro de rede
  e 5xx, abre com 5 falhas consecutivas, 30 s aberto (`:64-89`, `:143-154`); 4xx vira `*StatusError` sem afetar o breaker
  (`:164-166`); correlation via `correlation.Transport` (`:85`); métrica de estado (`:48-51`).
- **bff-auth decorators** (`bff/bff-auth/cmd/bff-auth/main.go:156-180`): cadeia (de fora para dentro) logging →
  circuit breaker → retry → metrics → cache Redis → cliente resty. Breaker `gobreaker` v1 com `DefaultIsSuccessful` que
  trata 4xx como sucesso (`bff/bff-auth/internal/infrastructure/resilience/circuit_breaker.go:48-75`); retry com backoff
  exponencial + jitter só em 408/429/502/503/504 e erro de rede (`bff/bff-auth/internal/adapters/out/http/decorator/company/retry_decorator.go:24-32`, `:52-70`).
  Cuidado: o cliente base já tem `resty.SetRetryCount(1)` (`bff/bff-auth/internal/adapters/out/http/client/auth_client.go:38-40`) — retry duplicado; não repetir isso.
- **ms-auth-go `usererasure`** (`ms/ms-auth-go/internal/adapters/in/rabbitmq/usererasure/user_erasure_consumer.go`): `amqp091-go`,
  `Start` não bloqueia o boot, reconexão a cada 5 s (`:64-115`), declara topologia + `Qos` + consume com ack manual (`:119-158`, `:170-178`).

**Recomendação:** usar o `resilient` do ms-auth-go (copiado para o guardian, sem decorators em camadas) para todos os
clientes HTTP de serviço. É mais simples e já resolve "retry só idempotente" no próprio cliente. Decorators só se um
cliente precisar de cache Redis (nenhum precisa aqui).

### 6.2 Por cliente

| Cliente Go | Base | Timeout | Retry | Breaker | Observações |
|---|---|---|---|---|---|
| ms-auth (token) | `resilient` | 5 s total (Java 2 s/5 s) | GET runtime-secret: 1 retry; POST token: não | sim, `ms-auth` | cache token por company com renovação 600 s antes; `singleflight` por company em vez de lock global; TTL no cache do secret (ex.: 1 h) e invalidar ao receber 401 |
| srv-llm-gateway | `resilient` | `context.WithTimeout` 45 s / 90 s (cancela de verdade) | **não** (POST caro, não idempotente em custo) | sim, `llm-gateway` (gateway fora → heurística imediata) | headers `X-Company-Id` (se houver), `X-Correlation-ID`, `Authorization`; ou `X-Api-Key` (aceito pelo gateway, `GW/adapters/inbound/http/auth.go:146-158`) para tirar a dependência do ms-auth. Decidir se aplica o rate limit LLM aqui (hoje só na fila) |
| GitHub | `resilient` | 20 s | só GET (1–2 retries); POST/PUT nunca | sim, `github-api` | rate limit Redis 10/s antes; tratar 403/429 com `Retry-After`/`X-RateLimit-Reset` como não-falha de infra; escapar path por segmento; paginar comentários se for corrigir |
| ms-communication | `resilient` | 8 s | não (POST) | opcional | só fallback; manter |
| Kubernetes | cliente próprio `net/http` (§6.3) | dial 8 s, request 15 s via `ctx` | só GET, em I/O/429/5xx (igual Fabric8, mas sem refazer mutação) | não | leituras devolvem erro tipado e o **use case** decide (corrige achado 4: erro de K8s ≠ "saudável") |
| RabbitMQ publish | `amqp091-go`, conexão com reconexão | — | não | não | **publisher confirms** no e-mail; `mandatory` + `NotifyReturn` para pegar exchange/rota inexistente (achado 6). Auditoria: assíncrona, best-effort, como hoje |
| RabbitMQ consumer | padrão `usererasure` | — | 3 tentativas in-process (1 s, 2 s) | — | Qos 1–10, 1 consumer; esgotou → publicar na DLQ com os headers `x-exception-*`/`x-original-*` e `Ack`, **ou** `Nack(requeue=false)` e deixar o DLX da fila rotear (mais simples, gera `x-death`). Opcional: idempotência `SET guardian:idem:incq:{trackingId} NX EX 86400` |
| Redis | `go-redis/v9` | 3 s | — | — | §3; manter fallback local |

### 6.3 Cliente Kubernetes sem client-go

**Como o Java resolve** (`J/infrastructure/config/KubernetesClientConfig.java:22-54`):
1. Se `app.kubernetes.kubeconfig-path` (`R/application.yml:82-83`, default vazio; o Helm não define) apontar para arquivo
   existente → `Config.fromKubeconfig(conteúdo)` (`:33-41`).
2. Senão → `Config.autoConfigure(null)` (`:44-47`): kubeconfig padrão (`KUBECONFIG`/`~/.kube/config`) se existir; senão
   service account in-cluster **(lib)**. Constantes confirmadas no `Config.class`: token
   `/var/run/secrets/kubernetes.io/serviceaccount/token`, CA `.../ca.crt`, namespace `.../namespace`, host/porta
   `KUBERNETES_SERVICE_HOST`/`KUBERNETES_SERVICE_PORT`.
3. Qualquer erro → de novo `autoConfigure` (`:48-53`). Em todos os casos aplica os timeouts (`:56-63`).
Em prod o pod não tem kubeconfig → sempre service account `ms-ai-guardian-sa`.

**Proposta Go** (`internal/adapters/out/http/kubernetes/`):
- **In-cluster** (padrão):
  - base URL `https://` + `net.JoinHostPort(os.Getenv("KUBERNETES_SERVICE_HOST"), os.Getenv("KUBERNETES_SERVICE_PORT"))`;
  - `tls.Config{RootCAs: pool com ca.crt}`; `Authorization: Bearer <token>`;
  - **reler o token do arquivo** periodicamente (ex.: a cada 1 min ou quando der 401): tokens projetados de SA expiram e
    o kubelet reescreve o arquivo; o Fabric8 também relê **(lib)**;
  - `http.Transport{DialContext: (&net.Dialer{Timeout: 8s}).DialContext, TLSHandshakeTimeout: 8s}` e `ctx` de 15 s por chamada.
- **Fora do cluster** (só se precisar; o fluxo do projeto é só produção): env `KUBECONFIG` ou flag de caminho →
  parse YAML mínimo (`gopkg.in/yaml.v3`): `current-context` → `cluster.server`, `certificate-authority-data`/
  `certificate-authority`, `insecure-skip-tls-verify`; `user.token` ou `client-certificate-data`+`client-key-data`.
  Plugin `exec` (EKS/GKE) fora de escopo.
- Endpoints: os da tabela §1.7, com estas mudanças deliberadas:
  - **restart:** `PATCH` `application/strategic-merge-patch+json`
    `{"spec":{"template":{"metadata":{"annotations":{"kubectl.kubernetes.io/restartedAt":"<RFC3339>"}}}}}` (o JSON
    Patch `add` do Fabric8 falha com 422 se o template não tiver `annotations` **(inferência)**; o merge patch não);
  - **rollback:** igual ao `kubectl rollout undo`: RS por `matchLabels`, ordenar por `deployment.kubernetes.io/revision`,
    pegar a anterior, remover `pod-template-hash` dos labels do template, `PATCH` JSON Patch `replace /spec/template`;
    erro claro se houver <2 revisões;
  - **scale:** `PATCH /apis/apps/v1/namespaces/{ns}/deployments/{name}` `application/merge-patch+json`
    `{"spec":{"replicas":N}}` — passa no RBAC atual (`patch` em `deployments`), ao contrário do `/scale` do Java (achado 3);
    ou acrescentar `deployments/scale` na Role;
  - **delete pod:** tratar 404 como sucesso (preserva o comportamento);
  - **events:** usar `fieldSelector=type=Warning,involvedObject.name={pod}` em vez de listar tudo;
  - **logs:** manter `tailLines=80` sem `previous` para paridade; avaliar `previous=true` quando o container está em
    CrashLoopBackOff (melhoria, não paridade).
- Tipos: structs Go mínimas só com os campos lidos (metadata.name/labels/annotations/ownerReferences, spec.replicas/
  selector/template, status.phase/containerStatuses/availableReplicas/readyReplicas/conditions, event fields). Decodificar
  com `json.Decoder` e ignorar o resto.

### 6.4 O que fica de fora de propósito
- Spring AI direto (Ollama/OpenAI/Anthropic): não portar (§1.8).
- Config Resilience4j do YAML: não reproduzir números; definir valores Go do zero (§0.1).
- Declaração da `ms-communication-exchange-dev` (§4.1): morta.
- `llm-cache-ttl-seconds`: sem uso (§3.6).
