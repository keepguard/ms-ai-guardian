# 03 — Libs, auditoria, métricas e OAuth (Fase 0 da migração para Go)

Levantamento sem código. Abreviações de caminho usadas nas referências:

- `J/` = `ms-ai-guardian/src/main/java/com/keepguard/ms_ai_guardian/`
- `R/` = `ms-ai-guardian/src/main/resources/`
- `L/` = `lib-go-common/` (tag mais recente: **v0.2.0**; ms-auth-go e ms-company-go ainda fixam `v0.1.0` — `ms-auth-go/go.mod:8`, `ms-company-go/go.mod:8`)

---

## 1. Dependências do `pom.xml` e uso real

| Dependência (pom.xml:linha) | Usada? | Evidência | Equivalente Go |
|---|---|---|---|
| spring-boot-starter-web (27) | Sim | 4 controllers, ex. `J/adapters/in/rest/incident/IncidentController.java:27-28` | Echo (padrão dos moldes, `ms-company-go/go.mod:9`) |
| actuator + micrometer-registry-prometheus (33-40) | Sim, só auto-config | `R/application.yml:146-163` (health probes + `/actuator/prometheus`); probes do helm em `/actuator/health/liveness|readiness` (`helm/templates/deployment.yaml:104,112,120`) | `prometheus/client_golang` + rotas de health iguais |
| data-jpa + postgresql (43-51) | Sim | 12 JPA entities em `J/infrastructure/persistence/entity/`; `ddl-auto: update` e schema `ms_ai_guardian` (`R/application.yml:22,27`) | pgx (`ms-company-go/go.mod:7`) — ver doc de banco |
| starter-amqp (54-57) | Sim | `@RabbitListener` em `J/adapters/in/messaging/IncidentQueueConsumer.java`; `RabbitTemplate` em auditoria, e-mail e enqueue (`J/adapters/out/audit/GuardianAuditPublisher.java:24`, `J/adapters/out/notification/EmailNotificationService.java:32`) | amqp091-go |
| spring-ai-ollama / openai starters (60-67; profile anthropic 226-232) | Sim, legado | `J/infrastructure/llm/SpringAiLlmAdapter.java:10,27` (ChatClient); default de prod é `gateway`/`none` (`R/application.yml:118`) | Nenhum: no Go basta o adapter HTTP do srv-llm-gateway; provedores diretos podem cair |
| fabric8 kubernetes-client (70-74) | Sim | `J/adapters/out/k8s/KubernetesInspectorService.java`, `KubernetesOpsAdapter.java`, `J/infrastructure/config/KubernetesClientConfig.java` | client-go |
| lombok (77-82) | Sim (compile-time) | `@RequiredArgsConstructor`/`@Slf4j` em todo lado | — |
| **spring-boot-starter-validation (85-88)** | **Não** | nenhum `jakarta.validation`/`@Valid`/`@NotBlank` no código (grep vazio); validação é manual, ex. regex de e-mail em `J/application/service/sre/AlertRecipientService.java:18,55-56` | — |
| springdoc-openapi (91-95) | Só anotações | `@Tag/@Operation` em `J/adapters/in/rest/diagnostic/DiagnosticController.java:20,27,35` | opcional |
| data-redis + commons-pool2 (98-105) | Sim | 4 adapters `J/infrastructure/redis/*`, `StringRedisTemplate`; pool lettuce em `R/application.yml:33-38` | go-redis |
| bucket4j-core (108-112) | Sim (fallback local) | `J/infrastructure/ratelimit/RateLimiterService.java:5,21,62-64` | `golang.org/x/time/rate` |
| spring-cloud-starter-openfeign (115-118) | Sim | 4 clients em `J/adapters/out/feign/` (Auth, Communication, GitHub, LlmGateway) | `net/http` + `correlation.Transport` |
| **spring-boot-starter-aop (120-123)** | **Não** | nenhum `@Aspect`, `@Async`, `@Cacheable` (grep vazio) | — |
| **resilience4j-* (124-148)** | **Não no código** | só config: instâncias `llm-gateway` e `github-api` em `R/application.yml:165-187` e health `circuitbreakers` (158-159); nenhum `@CircuitBreaker`/`@Retry` e nenhum import `io.github.resilience4j` no Java | não portar (ou decidir à parte; ms-auth-go usa `sony/gobreaker`, `ms-auth-go/go.mod:13`) |
| spring-boot-starter-test (151-155) | Testes | `src/test/**` | testify |

Timeouts dos Feign (precisam ir para os `http.Client` do Go): auth 2s/5s (`J/adapters/out/feign/AuthTokenClientConfig.java:18`), communication 2s/8s (`CommunicationMessageClientConfig.java:18`), GitHub 5s/20s (`GitHubClientConfig.java:18`), LLM gateway 5s/95s (`LlmGatewayClientConfig.java:18`).

## 2. lib-common / lib-security / lib-validation e segurança

- **Nenhuma das três libs Java é dependência**: o pom só tem `groupId com.keepguard` do próprio artefato (`pom.xml:11`) e nenhum `artifactId lib-*` (pom.xml:25-156). Confirmado: não usa lib-common (nem lib-security, nem lib-validation).
- **Sem Spring Security e sem JWT de entrada**: não há `spring-boot-starter-security` no pom, nenhum `SecurityFilterChain`, filtro, interceptor ou leitura de `Authorization` em controller (grep vazio). **Todas as rotas são abertas**:
  - `GET/PUT /api/v1/guardian/alert-recipients`, `PATCH /alert-recipients/{id}` — `J/adapters/in/rest/alertrecipient/AlertRecipientController.java:28,33,39`
  - `POST /api/v1/guardian/diagnose/async`, `POST /diagnose` — `J/adapters/in/rest/diagnostic/DiagnosticController.java:26,34`
  - `GET /incidents`, `GET /incidents/{id}`, `POST /incidents/{id}/actions` — `IncidentController.java:41,57,63`
  - `POST /api/v1/guardian/webhooks/github` — `J/adapters/in/rest/github/GitHubWebhookController.java:27`
- Identidade do usuário é **confiada em header** vindo do BFF, sem validação: `X-User-ID`, `X-User-Email`, `X-User-Role`, `X-Correlation-ID` (`IncidentController.java:68-71`), usados no audit da remediação.
- **Webhook do GitHub sem verificação de assinatura** (`X-Hub-Signature-256` não é lido; só `X-GitHub-Event` e `X-GitHub-Delivery`, `GitHubWebhookController.java:30-31`). Idempotência por delivery-id no Redis (`J/application/service/pr/HandlePrEventUseCase.java:93-98`).
- Ponto de atenção: o `AlertRecipientUseCaseService` tenta ler `codeUser`/`correlationId` do MDC (`J/application/service/sre/AlertRecipientUseCaseService.java:46,49`) e o publisher lê `tenantId`/`companyId` do MDC (`GuardianAuditPublisher.java:54-55`), mas **ninguém faz `MDC.put`** no serviço (grep: só esses `MDC.get`). Na prática esses campos saem sempre vazios.
- Uso de `Authorization` só **de saída**: GitHub (`J/adapters/out/github/GitHubApiClient.java:46`) e LLM gateway (`J/infrastructure/llm/GatewayLlmAdapter.java:93`).

**Para o Go:** manter paridade = sem `auth.Middleware`. Endurecer (JWT via `L/auth`, HMAC no webhook) é decisão separada — muda contrato com o BFF.

## 3. Anotações de log/métrica e auditoria manual

- `@LogOperation`, `@MetricsEndpoint`, `@Timed`, `@Counted`, `@Observed`: **nenhuma ocorrência** em `J/` (grep vazio).
- Toda auditoria é manual via `GuardianAuditPublisher.publish(action, outcome, correlationId, resourceType, resourceId[, actorType, actorCodeUser])` (`GuardianAuditPublisher.java:36-41`). Nenhuma chamada passa `reason`.

### 3.1 Lista completa de publicações

| # | action | outcome | resourceType | resourceId | actor | correlationId | Onde |
|---|---|---|---|---|---|---|---|
| 1 | `GUARDIAN_INCIDENT_OPENED` | SUCCESS | `INCIDENT` | id do incidente | SYSTEM | `incident.correlationId` | `J/application/service/AiDiagnosticService.java:94-95` |
| 2 | `GUARDIAN_CLUSTER_STORM_OPENED` | SUCCESS | `INCIDENT` | id do incidente | SYSTEM | `incident.correlationId` | `J/application/service/sre/ClusterStormService.java:163-164` |
| 3 | `GUARDIAN_INCIDENT_NORMALIZED` | SUCCESS | `INCIDENT` | id do incidente | SYSTEM | `incident.correlationId` | `J/application/service/sre/IncidentReconciliationService.java:110-111` |
| 4 | `GUARDIAN_REMEDIATION_REQUESTED` | SUCCESS | `INCIDENT` | incidentId | `USER` + codeUser=`X-User-ID` | header ou `incident.correlationId` | `J/application/service/sre/IncidentRemediationService.java:68-70` |
| 5 | `GUARDIAN_INCIDENT_DISMISSED` | SUCCESS | `INCIDENT` | incidentId | `USER` + X-User-ID | idem | `IncidentRemediationService.java:78-79` |
| 6 | `GUARDIAN_REMEDIATION_APPLIED` | SUCCESS | `INCIDENT` | incidentId | `USER` + X-User-ID | idem | `IncidentRemediationService.java:96-97` |
| 7 | `GUARDIAN_REMEDIATION_FAILED` | **FAILURE** | `INCIDENT` | incidentId | `USER` + X-User-ID | idem | `IncidentRemediationService.java:105-106` |
| 8 | `GUARDIAN_ALERT_SENT` / `GUARDIAN_ALERT_FAILED` | SUCCESS / FAILURE | `INCIDENT` | **serviceName** (não o id) | SYSTEM | correlationId do incidente ou UUID v3 do conteúdo | `J/adapters/out/notification/EmailNotificationService.java:256-261` (`dispatchEmail`) |
| 9 | `GUARDIAN_ALERT_SENT` / `GUARDIAN_ALERT_FAILED` | SUCCESS / FAILURE | `INCIDENT` | **serviceName** | SYSTEM | `incident.id` (`AlertFanoutService.java:121,146`) | `EmailNotificationService.java:60-67` (`sendHtmlTo`, sempre com `publishAudit=true` — `J/application/service/sre/AlertFanoutService.java:128-130,153-155`) |
| 10 | `GUARDIAN_GITHUB_WEBHOOK` | SUCCESS | `PR` | `"<repo>#<prNumber>"` | SYSTEM | `null` → UUID novo | `HandlePrEventUseCase.java:100-102` |
| 11 | `GUARDIAN_ALERT_RECIPIENT_UPSERTED` | SUCCESS | `ALERT_RECIPIENT` | id do destinatário | USER se MDC `codeUser`, senão SYSTEM (na prática sempre SYSTEM, §2) | MDC (na prática nulo → UUID novo) | `AlertRecipientUseCaseService.java:33,45-50` |
| 12 | `GUARDIAN_ALERT_RECIPIENT_PATCHED` | SUCCESS | `ALERT_RECIPIENT` | id do destinatário | idem | idem | `AlertRecipientUseCaseService.java:41,45-50` |

Observação: no remediation o `actorType` é forçado `"USER"` mesmo se `X-User-ID` vier vazio (`IncidentRemediationService.java:70`) — o evento sai `actor.type=USER` sem `codeUser`.

### 3.2 Formato publicado pelo Java

- Exchange: `keepguard.audit.exchange`, default `srv-audit-exchange-dev` (`GuardianAuditPublisher.java:30-31`); prod `srv-audit-exchange-prod` (`R/application-prod.yml:24`, env em `helm/templates/deployment.yaml:58-59`). Declarado `topic` durável uma vez (`GuardianAuditPublisher.java:89-97`).
- Routing key: `audit.event` (`GuardianAuditPublisher.java:33-34`; `R/application.yml:143`).
- Liga/desliga: `keepguard.audit.enabled` (`GuardianAuditPublisher.java:27-28,42-44`).
- Mensagem: header `X-Correlation-ID`, `PERSISTENT` (`GuardianAuditPublisher.java:78-81`); corpo via `Jackson2JsonMessageConverter` (`J/infrastructure/config/RabbitMQConfig.java:22-31`) → `content_type=application/json` + header `__TypeId__=java.util.HashMap`.
- Envio assíncrono sem limite (`CompletableFuture.runAsync`, `GuardianAuditPublisher.java:75`); falha só gera warn (83-85).
- Campos JSON (`GuardianAuditPublisher.java:48-74`):
  `eventId` (UUID v4), `occurredAt` (`Instant.toString()`), `schemaVersion: 1`, `sourceService: "ms-ai-guardian"`, `correlationId` (UUID novo se vazio, 45-47), `tenantId`/`companyId` (só se houver no MDC — nunca, §2), `action`, `outcome`, `actor: {type, codeUser?}` (default `SYSTEM`; se vier codeUser com SYSTEM vira `USER`, 64-73), `resource: {type, id}` (id `""` quando nulo, 74). Sem `reason`, `changes`, `metadata`, `requestId`, `actor.clientIp/deviceId/roles`.

### 3.3 Comparação com `L/audit`

| Ponto | Java guardian | `L/audit` | Compatível? |
|---|---|---|---|
| Envelope/campos | §3.2 | `Event` com os mesmos nomes JSON (`L/audit/audit_event.go:29-60`) | **Sim** — mesmo contrato que o srv-audit lê (`srv/srv-audit/internal/application/dto/audit_event.go:13-49`) |
| Exchange/rk/declare | topic durável, `audit.event` | idem (`L/audit/rabbit_audit_publisher.go:16,138`) | Sim |
| Header/persistência | `X-Correlation-ID`, persistent | idem (`rabbit_audit_publisher.go:116-118`) | Sim |
| `occurredAt` | `Instant.toString()` | `time.Time` RFC3339Nano (`audit_event.go:31`) | Sim (srv-audit faz `time.Time`, `audit_event.go:15` do srv-audit) |
| `__TypeId__` | enviado | não enviado | Sim — srv-audit não lê (grep vazio) |
| **actor.type default** | `SYSTEM` (`GuardianAuditPublisher.java:65`) | `ANONYMOUS` quando sem codeUser (`audit_event.go:91-97`) | **Diferença** — o adapter Go tem de setar `Actor.Type = audit.ActorSystem` (`L/audit/oplog_recorder.go:14`) |
| SYSTEM+codeUser → USER | sim (68-70) | não existe | adapter replica a regra |
| **correlationId vazio** | gera UUID (45-47) | `Normalize` **não** preenche correlationId (`audit_event.go:78-102`); só o `oplogRecorder` gera (`oplog_recorder.go:42-45`) | adapter tem de gerar (ou usar `correlation.FromContext`) |
| `resource.id` vazio | `"id": ""` | omitido (`omitempty`, `audit_event.go:59`) | Equivalente (srv-audit faz TrimSpace → "") |
| Fila | ilimitada | buffer 500, descarta se cheio (`rabbit_audit_publisher.go:17,74-82`) | Aceitável |
| Flag enabled | `keepguard.audit.enabled` | Exchange ou URL vazia → `NoopPublisher` (`rabbit_audit_publisher.go:50-52`) | Mapear a flag para Exchange vazio |
| Conexão | reaproveita a do Spring | abre conexão própria (`rabbit_audit_publisher.go:128`) | Ok (uma conexão a mais) |

**Conclusão:** compatível. Falta só um adapter fino no serviço (`GuardianAuditPublisher` Go) que preencha `Actor.Type` (SYSTEM/USER), `CorrelationID` (UUID se vazio) e chame `Publisher.Publish`. Nada precisa mudar na lib.

## 4. Métricas Micrometer manuais

- **Nenhuma.** Sem `MeterRegistry`, `Counter`, `Timer`, `Gauge`, `DistributionSummary` em `J/` (grep vazio).
- O que existe hoje é só auto-config do actuator: `/actuator/prometheus` exposto (`R/application.yml:160-163`) com métricas padrão do Spring (HTTP server, JVM, Hikari, RabbitMQ). O `resilience4j-micrometer` (`pom.xml:144-148`) pode registrar métricas de circuit breaker das instâncias configuradas (`R/application.yml:173-177`), mas nenhuma chamada passa por elas (§1) — não verificado em runtime.
- Para o Go: não há métrica de negócio a preservar. Painéis/alertas que usem métricas Java do guardian não foram verificados neste levantamento.

## 5. Lacunas da lib-go-common e uso por pacote

### 5.1 `oauthsecret` × `OAuthSecretCrypto.java` (decrypt)

| Passo | Java (`J/infrastructure/oauth/OAuthSecretCrypto.java`) | Go (`L/oauthsecret/oauthsecret.go`) | Igual? |
|---|---|---|---|
| Chave | `SHA-256(secretBase UTF-8)` (31, 40-42) | `sha256.Sum256([]byte(base))` (50) | Sim |
| Algoritmo | `AES/GCM/NoPadding`, tag 128 bits (15, 30-31) | `aes.NewCipher` + `cipher.NewGCM` (tag 16, nonce 12) (51-55) | Sim |
| Layout | Base64 std → IV 12 ‖ ct+tag (14, 24, 28-29) | `StdEncoding.DecodeString`, `packed[:12]` / `packed[12:]` (130, 134) | Sim |
| Tamanho mínimo | `<= 12` → erro (25-26) | `<= ivLength` → falha (131) | Sim |
| Base vazia | erro no `decrypt` (20-21) | erro no `New` (`ErrBaseRequired`, 46-48) | Equivalente (falha no boot em vez de na chamada) |
| Erro | lança exceção ("Falha ao descriptografar clientSecret", 36) | devolve `("", false)` (124-139) | Adapter converte `false` em erro |
| Trim da base | o chamador faz `trim()` (`AuthTokenAdapter.java:35`) | `New` não faz trim (só checa, 47) | Chamador Go tem de fazer `strings.TrimSpace` antes de `New` **e** antes do header |

**Cobre 100%** o algoritmo. O guardian-go deve usar `oauthsecret.New(base).Decrypt` em vez de copiar a cripto (srv-data-collector e bff-core têm cópias locais `oauthsecret.DecryptAESGCM`, `srv/srv-data-collector/internal/adapters/out/authclient/oauth_client.go:17,200`).

### 5.2 `comm` × envio para o ms-communication

- O guardian manda e-mail **primeiro por RabbitMQ direto** para o srv-email-google-sender (exchange `srv-email-google-sender-exchange-dev`, rk `email.google.send`, `EmailNotificationService.java:39-43,293-307`; payload `tenant_id, x_correlation_id, correlationId, to, subject, html`, 298-304).
- Só em falha usa **fallback HTTP** `POST {app.communication.url}/api/v1/messages/send` com headers `X-Company-Id`, `X-Correlation-ID` (`J/adapters/out/feign/CommunicationMessageClient.java:10-21`), sem `Authorization`, payload em `EmailNotificationService.java:271-283` (`messageType/communicationType: "EMAIL"`, `templateType: "ALERTA_SEGURANCA"`, `codeUser: "ADMIN_GUARDIAN"`).
- `L/comm` só tem os **enums** (`L/comm/comm.go:17,35,63` = `CommEmail`, `MessageEmail`, `TemplateAlertaSeguranca`). Não tem cliente HTTP nem publisher. **Cobre só as constantes**; o cliente HTTP e o publisher do e-mail ficam no serviço.
- Detalhe: `RabbitMQConfig` declara um `TopicExchange` `ms-communication-exchange-dev` (`J/infrastructure/config/RabbitMQConfig.java:14-20`; `R/application.yml:128-129`) que nenhum código publica (grep de `app.rabbitmq.exchange` só acha a declaração). Candidato a não portar.

### 5.3 Decisão por pacote para o ms-ai-guardian-go

| Pacote | Usar? | Motivo |
|---|---|---|
| `audit` | **Sim** | Publisher RabbitMQ compatível (§3.3). Usar `NewRabbitPublisher` + adapter local. `ActorMiddleware` opcional (só o remediation tem ator, vindo de `X-User-ID`, que é o mesmo header do middleware, `L/audit/request_actor.go:39`). |
| `oauthsecret` | **Sim** | Decrypt idêntico (§5.1). |
| `correlation` | **Sim** | Middleware de entrada + `Transport` nas chamadas a ms-auth/llm-gateway/ms-communication/GitHub (`L/correlation/correlation_transport.go:9-24`). Hoje o Java não tem filtro de correlation (grep vazio) — o middleware só acrescenta o header de resposta. |
| `comm` | Opcional | Só para as 3 constantes do fallback HTTP. |
| `oplog` | **Não** | Não há `@LogOperation`; usar `Run` criaria `operations_total`/`business_errors_total` que não existem hoje (`L/oplog/operation_logger.go:92-99`). |
| `httpmetrics` | Opcional | Não há `@MetricsEndpoint`; `api_requests_total` seria métrica nova. Seguir o que a skill `/new-app-go` fixar. |
| `apperr` | **Não** (paridade) | O guardian responde `{"error": "...", "message": "..."}` (`J/infrastructure/rest/GlobalExceptionHandler.java:14-25`), não ProblemDetail. Trocar muda contrato com o front. |
| `auth` | **Não** (paridade) | Não há JWT de entrada (§2). |
| `brdoc` | Não | Validação de e-mail própria (regex e mensagem "E-mail inválido", `AlertRecipientService.java:18,55-56`). |
| `codegen` | Não | Sem uso equivalente. |

### 5.4 O que falta na lib

1. **Cliente OAuth `client_credentials` com cache** (§6) — inexistente na lib e já duplicado em bff-core (`bff/bff-core/internal/adapters/outbound/http/client/bff_oauth_token.go:63-196`) e srv-data-collector (`oauth_client.go:55-267`). Guardian seria a 3ª cópia. Candidato a pacote novo (ex. `L/oauthclient`) — decisão do Rafael; alternativa é copiar o do srv-data-collector para o serviço.
2. Nada mais é bloqueante. Adapter de auditoria (SYSTEM + correlationId) é específico do serviço.

## 6. Cliente OAuth client_credentials (AuthTokenAdapter / AuthTokenClient)

**Quem usa:** só o `GatewayLlmAdapter`, para o `Authorization: Bearer` do srv-llm-gateway (`J/infrastructure/llm/GatewayLlmAdapter.java:93,134-149`). company = `APP_GUARDIAN_TENANT_ID` (`GatewayLlmAdapter.java:78`; `R/application.yml:101`). Se o tenant não for UUID, cai no catch (141-143). Se não houver token, usa o estático `LLM_GATEWAY_BEARER_TOKEN` (145-147; `R/application.yml:120`); se nem isso, chama sem Authorization.

**Config:** `AUTH_BASE_URL` (default `http://ms-auth:8081`), `AUTH_CLIENT_ID` (default `ms-ai-guardian`), `AUTH_CLIENT_SECRET_BASE` (secret k8s `keepguard-secret`), `AUTH_TOKEN_RENEW_BEFORE_SECONDS` (default 600) — `R/application.yml:133-137`; `helm/templates/deployment.yaml:78-86`. Sem secret base → desligado (`AuthTokenAdapter.java:39-41,45-47`).

**Fluxo:**

1. **Secret em runtime** (1ª vez por company): `GET {auth}/api/v1/auth/oauth/runtime/secret?clientId=<clientId>` com headers `X-Company-Id: <companyId>` e `X-Auth-Client-Secret-Base: <secretBase>` (`J/adapters/out/feign/AuthTokenClient.java:19-23`). Resposta `{clientId, secretEncrypted, status}` (30). Sem `secretEncrypted` → erro "OAuth client sem secret cifrado; recrie o client …" (`AuthTokenAdapter.java:87-89`). Decifra com `OAuthSecretCrypto` (90); `clientId` da resposta tem prioridade (91).
2. **Token:** `POST {auth}/api/v1/auth/oauth/token`, header `X-Company-Id`, corpo JSON `{"grantType":"client_credentials","clientId":…,"clientSecret":<plain>}` (`AuthTokenClient.java:25-28`; `AuthTokenAdapter.java:60-65`). Resposta lida: `{accessToken, expiresIn}` (`AuthTokenClient.java:32`). Rotas e nomes de campo conferidos no ms-auth-go (`ms-auth-go/internal/adapters/in/http/routes_oauthclient.go:25-26`; `.../oauthclient/dto/oauthclient_request_dto.go:23-25`; `.../dto/oauthclient_response_dto.go:29,31,50-52`; header em `.../oauthclient_query_handlers.go:17`).
3. **Cache:**
   - Token por `companyId` em `ConcurrentHashMap`; reaproveita enquanto `agora + renewBefore < expiry` (`AuthTokenAdapter.java:25,49-51`). Renovação sob `synchronized(this)` global com double-check (53-57).
   - `expiresIn <= 0` → assume 3600 s (69). Token sofre `trim` (70-71).
   - Secret decifrado por company, **sem expiração e sem invalidação** (mesmo em 401) (`AuthTokenAdapter.java:26,79-95`).
   - Qualquer erro → `Optional.empty()` + warn, sem retry (72-75).
4. Timeouts: connect 2s / read 5s (`AuthTokenClientConfig.java:18`).

**Lib Go:** não tem equivalente (só a cripto, §5.1). As cópias Go existentes seguem o mesmo fluxo (`srv/srv-data-collector/internal/adapters/out/authclient/oauth_client.go:96-145,168-267`), com diferenças a observar se for reaproveitar: chave de cache company+agente (148-154), envia `agentId/agentCode` (217-222), e **não** tem o default de 3600 s para `expiresIn` (136).
