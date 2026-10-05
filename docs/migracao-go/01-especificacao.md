# ms-ai-guardian — Especificação do comportamento atual (Java) para migração Go

Data: 2026-10-04. Fonte: `keepguard-core/backend/ms/ms-ai-guardian` (Spring Boot 3.5.3, `pom.xml:8`; artefato `1.0.52`, `pom.xml:13`; Java 25, `pom.xml:18`; Spring AI 1.0.0-M6, `pom.xml:19`; Spring Cloud 2025.0.3, `pom.xml:20`; Fabric8 7.3.2, `pom.xml:21`; Bucket4j 8.10.1, `pom.xml:108-112`; Resilience4j 2.2.0, `pom.xml:124-148`; springdoc 2.3.0, `pom.xml:91-95`). **Não depende de lib-common** (`pom.xml:25-156`).

Abreviações (relativas a `src/main/java/com/keepguard/ms_ai_guardian/`): `ctrl/` = `adapters/in/rest/`, `in/` = `adapters/in/`, `out/` = `adapters/out/`, `app/` = `application/`, `svc/` = `application/service/`, `dom/` = `domain/`, `infra/` = `infrastructure/`, `res/` = `src/main/resources/`, `test/` = `src/test/java/com/keepguard/ms_ai_guardian/`.

Legenda: **[código]** lido diretamente (padrão, quando não marcado); **[framework]** comportamento padrão de Spring/Jackson/Hibernate/Spring Data/Spring AMQP inferido — validar com chamada real antes de congelar o contrato Go.

Documentos irmãos: `02-consumidores-e-infra.md`, `03-libs.md`, `04-resiliencia-integracoes.md`. Já existe um esqueleto Go em `backend/ms/ms-ai-guardian-go/` (não analisado aqui).

---

## 0. Resumo executivo (maiores riscos para a reescrita)

1. **9 rotas HTTP de negócio** em 4 controllers (seção 1), **sem autenticação** (sem Spring Security no `pom.xml:25-156`), **sem Bean Validation** (nenhum `@Valid`/`@NotNull` no código; validação é manual). Webhook do GitHub **sem verificação de assinatura** (`ctrl/github/GitHubWebhookController.java:29-70`).
2. **Contrato de erro mínimo**: só `IllegalArgumentException` → 400 `{"error":"BAD_REQUEST","message":...}` e `ResponseStatusException` → `{"error":"404 NOT_FOUND","message":...}` (`infra/rest/GlobalExceptionHandler.java:14-25`). Todo o resto cai no `/error` padrão do Spring Boot (seção 2).
3. **MDC nunca é preenchido** (nenhum `MDC.put` em `src/main`): o ator das auditorias de destinatário é sempre `SYSTEM` e o `correlationId` sai aleatório (`svc/sre/AlertRecipientUseCaseService.java:45-51`); `tenantId/companyId` nunca vão no evento de auditoria (`out/audit/GuardianAuditPublisher.java:54-61`).
4. **Pipeline de PR cria branch órfã**: o `CoderAgent` cria a branch no GitHub **antes** de achar o arquivo e de gerar o patch (`svc/agents/CoderAgentService.java:71-91`). Com LLM `none` (default) o patch é idêntico → retorna sem PR, deixando `fix/guardian-<repo>-<8hex>` para trás — e isso se repete a cada varredura enquanto o incidente não for notificado (seção 4.1, 10).
5. **Qualquer merge humano em qualquer repo com webhook dispara `rollout restart`** do deployment homônimo ao repo em produção (`svc/agents/DeployerAgentService.java:28-50`), mesmo sem PR do Guardian (cria linha "placeholder").
6. **Regras de classificação do banco congelam**: o seed só roda com a tabela vazia (`infra/classification/ClassificationCatalog.java:60-63`) e o banco tem prioridade sobre o YAML (`:26-31`). Mudanças no `classification-rules.yml` não chegam a produção.
7. **E-mail em prod vai para exchange `-dev`**: `app.rabbitmq.email-exchange: srv-email-google-sender-exchange-dev` (`res/application.yml:130`) não é sobrescrito no `application-prod.yml` nem no Helm (`helm/templates/deployment.yaml:27-97`). Validar o env real do pod (doc 02).
8. **Status legados**: `IncidentStatus` tem 21 valores (`dom/enums/IncidentStatus.java:3-25`), mas o código só grava 5 (`DETECTED`, `AWAITING_HUMAN`, `ACTION_RUNNING`, `NORMALIZED`, `DISMISSED`). Linhas antigas em outros status nunca são reconciliadas, mas são tratadas como "abertas" no fingerprint (seção 3.3).
9. **Schema via Hibernate `ddl-auto: update`** (`res/application.yml:22`), sem Flyway, 12 tabelas sem índices secundários nem FKs (seção 5). Hibernate 6 provavelmente gerou `CHECK` nas colunas enum [framework] — dump do schema de prod é obrigatório antes da Fase 1.
10. **Resilience4j configurado e nunca usado**: `resilience4j.*` (`res/application.yml:165-187`) sem nenhum `@CircuitBreaker`/`@Retry` no código. `app.guardian.redis.llm-cache-ttl-seconds` (`res/application.yml:109`) também não é lido por ninguém.

---

## 1. Endpoints HTTP

### 1.0 Regras gerais

- **Porta** `8088` (`res/application.yml:2`, `res/application-local.yml:2`, `res/application-prod.yml:2`; Helm `SERVER_PORT` em `helm/templates/deployment.yaml:30-31`). Sem context-path.
- **Total: 9 rotas de negócio** — AlertRecipient 3 (`ctrl/alertrecipient/AlertRecipientController.java:28,33,39`), Diagnostic 2 (`ctrl/diagnostic/DiagnosticController.java:26,34`), Incident 3 (`ctrl/incident/IncidentController.java:41,57,63`), GitHub webhook 1 (`ctrl/github/GitHubWebhookController.java:27`).
- **Infra exposta**: Actuator `health,info,prometheus` (`res/application.yml:160-163`) com probes `/actuator/health/liveness` e `/readiness` (`res/application.yml:147-155`; usados em `helm/templates/deployment.yaml:102-123`); health de Redis desligado (`res/application.yml:156-157`), health de circuit breakers ligado (`:158-159`). Springdoc ativo por padrão (`pom.xml:91-95`) → `/v3/api-docs`, `/swagger-ui/index.html` [framework].
- **Sem filtro de correlação, sem interceptor, sem segurança** (nenhum `OncePerRequestFilter`/`HandlerInterceptor` no código).
- **Serialização (respostas)**: não há `spring.jackson.*` no YAML nem `ObjectMapper` próprio → mapper padrão do Boot [framework]:
  - Nomes JSON = nomes dos campos Lombok `@Data` em camelCase (nenhum `@JsonProperty` no código). Booleans primitivos `enabled`, `emailSent`, `notificationSent`, `forceSendEmail` saem com esse nome (getter `isX`). `k8sConclusion` sai como `k8sConclusion`.
  - **Nulos são serializados** (`null`), não há `Include.NON_NULL` nos DTOs de resposta (o único `@JsonInclude` é no DTO de saída para o gateway LLM, `infra/llm/GatewayLlmDtos.java:12`).
  - `LocalDateTime` → string ISO-8601 **sem offset** (ex. `"2026-10-04T13:05:07.123456"`), `WRITE_DATES_AS_TIMESTAMPS` desligado pelo Boot [framework — validar formato exato da fração]. Horário = relógio da JVM (container UTC) via `LocalDateTime.now()` e `@CreationTimestamp`.
  - `UUID` → string minúscula; enums → `name()`; `long` → número.
  - `FAIL_ON_UNKNOWN_PROPERTIES=false` (default Boot) → campos extras no request são ignorados [framework].
- **Entrada**: todos os `@RequestBody` são obrigatórios (default `required=true`) → corpo ausente/JSON inválido = `HttpMessageNotReadableException` → 400 do `/error` (seção 2) [framework]. `@PathVariable UUID` inválido → `MethodArgumentTypeMismatchException` → 400 do `/error` [framework].

### 1.1 AlertRecipientController — base `/api/v1/guardian` (`ctrl/alertrecipient/AlertRecipientController.java:20-23`)

| # | Método/Path | Entrada | Sucesso | Linhas |
|---|---|---|---|---|
| 1 | GET `/alert-recipients` | — | 200 `AlertRecipientResponseDTO[]` | `:28-31` |
| 2 | PUT `/alert-recipients` | body `AlertRecipientUpsertRequestDTO` | 200 `AlertRecipientResponseDTO` | `:33-37` |
| 3 | PATCH `/alert-recipients/{id}` | path `id` UUID + body `AlertRecipientUpsertRequestDTO` | 200 `AlertRecipientResponseDTO` | `:39-44` |

- Request `AlertRecipientUpsertRequestDTO` (`ctrl/alertrecipient/dto/request/AlertRecipientUpsertRequestDTO.java:6-10`): `{"email": string, "label": string, "enabled": boolean|null}` — sem anotações de validação. Mapeado 1:1 para o comando (`ctrl/alertrecipient/mapper/AlertRecipientAdapterMapper.java:14-21`).
- Response `AlertRecipientResponseDTO` (`ctrl/alertrecipient/dto/response/AlertRecipientResponseDTO.java:15-22`): `{"id": uuid, "email": string, "enabled": boolean, "label": string|null, "createdAt": LocalDateTime, "updatedAt": LocalDateTime}`.
- GET: **tem efeito colateral** — chama `listEnabledOrSeed()` antes de listar (`svc/sre/AlertRecipientService.java:47-50`), que pode inserir o destinatário default (seção 4.9). Ordena por `createdAt ASC`, inclui desabilitados (`infra/persistence/spring/GuardianAlertRecipientSpringRepository.java:13`).
- PUT: `enabled` nulo → `true` (`svc/sre/AlertRecipientUseCaseService.java:30`). Regras e mensagens na seção 4.9.
- PATCH: **ignora `email` e `label`**; só usa `enabled` (nulo → `true`) (`svc/sre/AlertRecipientUseCaseService.java:38-43`). Id inexistente → `IllegalArgumentException("Destinatário não encontrado")` → **400** (não 404) (`svc/sre/AlertRecipientService.java:79-80`).

### 1.2 DiagnosticController — base `/api/v1/guardian` (`ctrl/diagnostic/DiagnosticController.java:17-21`)

| # | Método/Path | Entrada | Sucesso | Linhas |
|---|---|---|---|---|
| 4 | POST `/diagnose/async` | body `ManualDiagnoseRequestDTO` | **202** `DiagnosticEnqueueResponseDTO` | `:26-32` |
| 5 | POST `/diagnose` | body `ManualDiagnoseRequestDTO` | 200 `DiagnosticResultResponseDTO` | `:34-39` |

- Request `ManualDiagnoseRequestDTO` (`ctrl/diagnostic/dto/request/ManualDiagnoseRequestDTO.java:12-18`): `{"namespace","podName","serviceName","errorReason": string, "forceSendEmail": boolean}` (primitivo: ausente/null → `false` [framework]). Sem validação.
- Defaults (`ctrl/diagnostic/mapper/DiagnosticAdapterMapper.java:14-33`): `namespace` nulo → `"keepguard"`; `serviceName` nulo → `podName`; `errorReason` nulo → `"MANUAL_TRIGGER"` (sync) ou `"MANUAL_ASYNC_TRIGGER"` (async). Strings vazias **não** são tratadas como nulas.
- Response sync `DiagnosticResultResponseDTO` (`ctrl/diagnostic/dto/response/DiagnosticResultResponseDTO.java:16-27`): `{"incidentId": uuid, "podName", "namespace", "serviceName": string, "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO", "errorReason", "rootCause", "recommendedAction": string, "technicalDetails": string[], "notificationSent": boolean}`. `rootCause` = `aiRootCauseAnalysis`, `recommendedAction` = `aiRecommendedAction` (que guarda o `riskNotes` da IA), `technicalDetails` = eventos Warning (`svc/AiDiagnosticService.java:165-178`).
- Response async `DiagnosticEnqueueResponseDTO` (`ctrl/diagnostic/dto/response/DiagnosticEnqueueResponseDTO.java:14-22`): `{"trackingId": uuid aleatório, "namespace","podName","serviceName","errorReason": string, "forceSendEmail": boolean, "enqueuedTimestamp": epoch ms}` (`infra/messaging/IncidentEnqueueAdapter.java:20-43`). Publica na fila antes de responder (seção 4.17).
- Sync com `podName` e `serviceName` nulos: não há validação; o fluxo quebra em `serviceName.contains(...)` (`svc/AiDiagnosticService.java:133`) ou antes no insert (`pod_name NOT NULL`, `infra/persistence/entity/IncidentJpaEntity.java:34-35`) → 500 do `/error`.

### 1.3 IncidentController — base `/api/v1/guardian` (`ctrl/incident/IncidentController.java:27-31`)

| # | Método/Path | Entrada | Sucesso | Linhas |
|---|---|---|---|---|
| 6 | GET `/incidents` | query params (abaixo) | 200 `PaginatedIncidentResponseDTO` | `:41-55` |
| 7 | GET `/incidents/{id}` | path `id` UUID | 200 `IncidentDetailResponseDTO` | `:57-61` |
| 8 | POST `/incidents/{id}/actions` | path `id` UUID, body `ExecuteActionRequestDTO`, headers opcionais | 200 `IncidentActionExecutionResponseDTO` | `:63-74` |

**GET `/incidents`** — recebe `Map<String,String>` de todos os params (primeiro valor de cada) e só repassa as chaves `page,size,from,to,status,severity,serviceName,namespace,k8sConclusion,errorReason,correlationId,q,sort,dir` não vazias (`:33-36,45-52`); `namespace` ausente → `"keepguard"` (`:53`) — não há como listar todos os namespaces.

| Param | Default / regra | Linhas |
|---|---|---|
| `page` | `0`; não numérico → 0; negativo → `PageRequest.of` lança `IllegalArgumentException` → **400** `{"error":"BAD_REQUEST","message":"Page index must not be less than zero"}` [framework] | `svc/sre/IncidentQueryService.java:42,49,122-128` |
| `size` | `20`, clamp `[1,100]`; não numérico → 20 | `:43` |
| `sort` | ∈ `createdAt,lastSeenAt,severity,status,serviceName`, senão `createdAt` | `:32,44-47` |
| `dir` | `asc` (case-insensitive) → ASC; qualquer outro → DESC | `:48` |
| `status`, `severity` | `Enum.valueOf` exato (case-sensitive); inválido → filtro ignorado | `infra/persistence/IncidentRepositoryAdapter.java:85-86,119-130` |
| `serviceName`, `k8sConclusion`, `errorReason`, `correlationId` | igualdade exata | `:87-90,112-117` |
| `from`, `to` | `createdAt >= from`, `createdAt <= to`; parse `LocalDateTime.parse` ISO, senão `OffsetDateTime.parse(...).toLocalDateTime()` (**descarta o offset sem converter**), senão ignora | `:91-98,132-145` |
| `q` | `lower(serviceName) LIKE %q% OR lower(podName) LIKE %q% OR lower(coalesce(aiSummary,'')) LIKE %q%`, com `q.toLowerCase()`; `%`/`_` não escapados | `:99-107` |

Ordenação de `severity`/`status` é **alfabética** (coluna varchar), não pela ordem do enum. Sem critério de desempate → ordem de empates não determinística [framework].

Response `PaginatedIncidentResponseDTO` (`ctrl/incident/dto/response/PaginatedIncidentResponseDTO.java:14-20`): `{"content": IncidentListItem[], "page": int, "size": int, "totalElements": long, "totalPages": int}` — `page/size` = os efetivamente usados (`svc/sre/IncidentQueryService.java:51-57`).

`IncidentListItemResponseDTO` (`ctrl/incident/dto/response/IncidentListItemResponseDTO.java:17-31`): `{"id": uuid, "namespace", "serviceName", "podName": string, "status": IncidentStatus, "severity": IncidentSeverity, "k8sConclusion": string|null, "errorReason": string, "occurrencesCount": int, "emailSent": boolean (= notificationSent), "lastSeenAt", "createdAt", "normalizedAt": LocalDateTime|null}` (`svc/sre/IncidentQueryService.java:104-120`).

**GET `/incidents/{id}`** — não encontrado → `ResponseStatusException(404, "Incidente não encontrado")` (`svc/sre/IncidentQueryService.java:61-62`). Response `IncidentDetailResponseDTO` (`ctrl/incident/dto/response/IncidentDetailResponseDTO.java:16-89`):

```json
{
  "incident": IncidentListItem,
  "aiRootCause": string|null, "aiSummary": string|null, "aiRecommendedAction": string|null,
  "investigationSource": "LLM"|"HEURISTIC_FALLBACK"|null, "correlationId": string,
  "healthyStreak": int, "capturedLogsSnippet": string|null,
  "evidence":    [{"id","kind","payloadJson","createdAt"}],                     // createdAt DESC
  "suggestions": [{"id","actionType","label","risk","enabled","disabledReason","aiRationale","payloadJson"}], // createdAt ASC
  "executions":  [{"id","suggestionId","actorUserId","outcome","errorMessage","createdAt"}], // createdAt DESC
  "deliveries":  [{"email","outcome","kind","sentAt"}],                         // sentAt DESC
  "timeline":    [{"eventType","detail","createdAt"}]                           // createdAt ASC
}
```
Ordens em `svc/sre/IncidentQueryService.java:72,77,85,91,96`. `actionType`/`risk`/`outcome`/`eventType` são `name()` dos enums (`:79-80,93,98`). Listas nunca são `null` (mapper troca por `[]`, `ctrl/incident/mapper/IncidentAdapterMapper.java:90-118`).

**POST `/incidents/{id}/actions`**:
- Body `ExecuteActionRequestDTO` (`ctrl/incident/dto/request/ExecuteActionRequestDTO.java:8-11`): `{"suggestionId": uuid, "confirmation": string}`.
- Headers lidos, todos opcionais (`ctrl/incident/IncidentController.java:68-71`): `X-User-ID`, `X-User-Email`, `X-User-Role`, `X-Correlation-ID`.
- Response `IncidentActionExecutionResponseDTO` (`ctrl/incident/dto/response/IncidentActionExecutionResponseDTO.java:15-28`): `{"id","incidentId","suggestionId": uuid, "actorUserId","actorEmail","actorRole","correlationId": string, "outcome": "SUCCESS", "beforeJson","afterJson": string (JSON serializado), "errorMessage": null, "createdAt": LocalDateTime}`.
- Erros na seção 4.4. `suggestionId` nulo → `findById(null)` → `IllegalArgumentException` traduzido pelo Spring Data para `InvalidDataAccessApiUsageException` → **500** do `/error` [framework — validar].

### 1.4 GitHubWebhookController — base `/api/v1/guardian/webhooks` (`ctrl/github/GitHubWebhookController.java:17-25`)

| # | Método/Path | Entrada | Sucesso | Linhas |
|---|---|---|---|---|
| 9 | POST `/github` | headers `X-GitHub-Event` (default `"ping"`), `X-GitHub-Delivery` (opcional); body texto cru | 200 `text/plain` | `:27-70` |

Fluxo (`:34-69`):
1. `beginDelivery(deliveryId)` — **antes** de parsear o corpo. Delivery vazio/nulo → segue; senão `SET NX guardian:idem:gh:<id>` TTL 86400 s (`svc/pr/HandlePrEventUseCase.java:93-98`). Duplicado → 200 `"Entrega duplicada ignorada."`.
2. `readTree(rawPayload)`; `repoName = repository.name`.
3. `pull_request`: `action`, `number` (int), `pull_request.merged` (default false), `sender.login` → `onPullRequest` (seção 4.13).
4. `pull_request_review_comment` / `issue_comment`: só se `action` = `created` (case-insensitive); `comment.body`, `comment.id` (como texto), `comment.user.login`; `prNumber = issue.number` se existir `issue`, senão `pull_request.number` → `onComment`.
5. Outros eventos (incl. `ping`): só log debug.
6. Sucesso → 200 `"Evento processado pelo KeepGuard Multi-Agent Guardian."`. Qualquer exceção (JSON inválido ou erro no processamento) → **400** `"Erro ao processar payload: " + e.getMessage()`.

Processamento é **síncrono** dentro do request (inclui chamadas a GitHub/LLM). Content-Type `text/plain;charset=UTF-8` [framework]. Não há validação de `X-Hub-Signature-256`. Como a idempotência é gravada antes do parse, um retry do GitHub de uma entrega que falhou recebe "Entrega duplicada ignorada.".

---

## 2. Contrato de erro

### 2.1 `GlobalExceptionHandler` (`infra/rest/GlobalExceptionHandler.java:11-26`)

| Exceção | Status | Corpo | Linhas |
|---|---|---|---|
| `IllegalArgumentException` | 400 | `{"error":"BAD_REQUEST","message": ex.getMessage()}` | `:14-18` |
| `ResponseStatusException` | `ex.getStatusCode()` | `{"error": ex.getStatusCode().toString(), "message": ex.getReason() ?: "Erro"}` | `:20-25` |

- `HttpStatus.toString()` = `"<código> <NOME_DO_ENUM>"` → ex. `"404 NOT_FOUND"`, `"409 CONFLICT"`, `"400 BAD_REQUEST"`, `"502 BAD_GATEWAY"` [framework].
- Corpo é `Map.of(...)` → **ordem das chaves varia por execução da JVM** (iteração de `ImmutableCollections.MapN` usa SALT aleatório) [framework]. Go pode fixar `error, message`.
- `IllegalArgumentException` com `message == null` → `Map.of` lança NPE → vira 500 [framework].
- Mensagens conhecidas: seções 4.4 e 4.9.

### 2.2 Erros não mapeados → `/error` do Spring Boot [framework]

Sem `server.error.*` no YAML → `DefaultErrorAttributes` com `include-message=never`, `include-stacktrace=never`:
```json
{"timestamp":"2026-10-04T15:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/v1/guardian/diagnose"}
```
- `timestamp` é `java.util.Date` serializado pelo Jackson do Boot (`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`, ex. `+00:00`).
- Casos que caem aqui: corpo ausente/JSON inválido (400 "Bad Request"), UUID inválido no path (400), método não suportado (405), rota inexistente (404 via `NoResourceFoundException`), media type (415), qualquer `RuntimeException` (500).

### 2.3 Erro de validação
Não existe: nenhum DTO tem anotação de Bean Validation e nenhum controller usa `@Valid`. Não há formato de lista de erros de campo.

---

## 3. Domínio

### 3.1 Entidades de domínio (POJOs Lombok, sem regras)

| Entidade | Campos | Arquivo |
|---|---|---|
| `Incident` | `id, namespace, podName, serviceName, errorReason, severity, status, capturedLogsSnippet, aiRootCauseAnalysis, aiRecommendedAction, fingerprint, occurrencesCount (default 1), lastSeenAt, targetRecipientEmail, notificationSent, notificationSentAt, k8sConclusion (String), investigationSource, correlationId, healthyStreak (default 0), normalizedAt, closedBy, reopenedFromId, aiSummary, createdAt, updatedAt` | `dom/entity/Incident.java:19-73` |
| `IncidentEvidence` | `id, incidentId, kind, payloadJson, createdAt` | `dom/entity/IncidentEvidence.java:15-26` |
| `IncidentActionSuggestion` | `id, incidentId, actionType, label, risk, enabled, disabledReason, aiRationale, payloadJson, createdAt` | `dom/entity/IncidentActionSuggestion.java:17-37` |
| `IncidentActionExecution` | `id, incidentId, suggestionId, actorUserId, actorEmail, actorRole, correlationId, outcome (String), beforeJson, afterJson, errorMessage, createdAt` | `dom/entity/IncidentActionExecution.java:15-39` |
| `IncidentAlertDelivery` | `id, incidentId, email, outcome, kind, correlationId, sentAt` | `dom/entity/IncidentAlertDelivery.java:16-30` |
| `IncidentLifecycleEvent` | `id, incidentId, eventType, detail, correlationId, createdAt` | `dom/entity/IncidentLifecycleEvent.java:16-28` |
| `GuardianAlertRecipient` | `id, email, enabled, label, createdAt, updatedAt` | `dom/entity/GuardianAlertRecipient.java:15-27` |
| `PullRequestLifecycle` | `id, incidentId, repoName, branchName, baseBranch, prNumber, prUrl, filePath, aiReviewed, aiApproved, aiReviewFeedback, humanApproved, mergedByHuman, deployedToK8s, lastProcessedCommentId, status, createdAt, updatedAt` | `dom/entity/PullRequestLifecycle.java:14-50` |
| `ProcessedComment` | `id, commentId, prNumber, processedAt` | `dom/entity/ProcessedComment.java:13-21` |
| `PromptTemplate` | `id, promptKey, version, body, status, checksum, createdAt, updatedAt` | `dom/entity/PromptTemplate.java:17-33` |
| `LlmInvocation` | `id, incidentId, promptKey, promptVersion, model, inputHash, output, latencyMs, fallbackUsed, createdAt` | `dom/entity/LlmInvocation.java:17-37` |
| `ClassificationRuleEntity` | `id, ruleKey, priority, verdict, requiresCodePr, errorContains, logsContains (String "\n"-separado), summaryTemplate, explanationTemplate, suggestedActionTemplate, enabled, createdAt, updatedAt` | `dom/entity/ClassificationRuleEntity.java:18-44` |

`humanApproved` e `lastProcessedCommentId` nunca são escritos com valor diferente do default (só lidos/copiados nos mappers).

### 3.2 Enums (todos os valores)

| Enum | Valores (ordem) | Métodos | Arquivo |
|---|---|---|---|
| `IncidentStatus` | `QUEUED, RATE_LIMITED_WAITING, EVALUATING_BUSINESS, GENERATING_ARCHITECTURE, CODING_HOTFIX, QA_CERTIFYING, PR_OPENED, AWAITING_HUMAN_APPROVAL, DEPLOYING_K8S, RESOLVED, REJECTED_DATA_ISSUE, FAILED_DLQ, DETECTED, DIAGNOSING, DIAGNOSED, NOTIFIED, AWAITING_HUMAN, ACTION_RUNNING, NORMALIZED, DISMISSED, IGNORED` | — | `dom/enums/IncidentStatus.java:3-25` |
| `IncidentSeverity` | `CRITICAL, HIGH, MEDIUM, LOW, INFO` | — | `dom/enums/IncidentSeverity.java:3-9` |
| `PullRequestStatus` | `OPEN, CHANGES_REQUESTED, AI_APPROVED, MERGED_BY_HUMAN, DEPLOYED, CLOSED` | `isActive()` = OPEN/CHANGES_REQUESTED/AI_APPROVED; `active()` = EnumSet desses 3 | `dom/enums/PullRequestStatus.java:6-21` |
| `K8sConclusion` | `CONTROLLER_ALREADY_RETRYING, REPLICAS_INTENTIONALLY_ZERO, UNSCHEDULABLE, IMAGE_OR_CONFIG, NODE_FAILURE, NO_CONTROLLER, TRANSIENT_INFRA_RECOVERABLE` | — | `dom/enums/K8sConclusion.java:3-11` |
| `RemediationActionType` | `RECREATE_POD("Recriar o pod"), ROLLOUT_RESTART("Rollout restart"), ROLLBACK_REVISION("Rollback da revisão"), SCALE_REPLAY("Zerar e subir réplicas"), DISMISS("Dispensar incidente")` | `label()` | `dom/enums/RemediationActionType.java:3-19` |
| `ActionRisk` | `LOW("baixo"), HIGH("alto"), DESTRUCTIVE("destrutivo")` | `label()` | `dom/enums/ActionRisk.java:3-17` |
| `ClassificationVerdict` | `INFRASTRUCTURE_FAULT, DATA_INCONSISTENCY, BUSINESS_RULE_VIOLATION, CODE_DEFECT` | — | `dom/enums/ClassificationVerdict.java:3-8` |
| `LifecycleEventType` | `DETECTED, INVESTIGATED, ALERTED, ACTION_APPLIED, ACTION_FAILED, HEALTH_CHECK_PASS, HEALTH_CHECK_FAIL, NORMALIZED, DISMISSED, REOPENED` | — | `dom/enums/LifecycleEventType.java:3-14` |
| `ClosedBy` | `WATCHER, HUMAN` | — | `dom/enums/ClosedBy.java:3-6` |
| `DeliveryOutcome` | `SENT, FAILED` | — | `dom/enums/DeliveryOutcome.java:3-6` |
| `InvestigationSource` | `LLM, HEURISTIC_FALLBACK` | — | `dom/enums/InvestigationSource.java:3-6` |
| `NotificationKind` | `INCIDENT_DIAGNOSTIC("incident-diagnostic"), PR_OPENED("pr-opened"), PR_READY_FOR_APPROVAL("pr-ready-for-approval"), COMMENT_REPLIED("comment-replied"), DEPLOY_STARTED("deploy-started"), DEPLOY_COMPLETED("deploy-completed"), DATA_INCONSISTENCY("data-inconsistency"), INFRASTRUCTURE_ALERT("infrastructure-alert"), MESA("mesa")` | `templateName()` | `app/port/out/notification/NotificationKind.java:3-23` |
| `RateLimiterPort.Bucket` | `GITHUB, LLM, EMAIL` | — | `app/port/out/cache/RateLimiterPort.java:5-7` |
| `QaAutomationAgentService.TestStatus` | `PASSED, FAILED, OUT_OF_SCOPE` | — | `svc/agents/QaAutomationAgentService.java:161-165` |

Strings livres usadas como "enum": `IncidentEvidence.kind` ∈ `CLUSTER_FACTS` (`svc/sre/IncidentInvestigationRecorder.java:31`), `STORM_ASSESSMENT` (`svc/sre/ClusterStormService.java:181`), `STORM_RECOVERED` (`svc/sre/IncidentReconciliationService.java:93`), `NORMALIZED_FACTS` (`:101`). `IncidentAlertDelivery.kind` ∈ `OPENED, NORMALIZED, ACTION, STORM_OPENED, STORM_NORMALIZED` (`svc/sre/AlertFanoutService.java:52,64,76,100,114`). `IncidentActionExecution.outcome` ∈ `SUCCESS, FAILURE` (`svc/sre/IncidentRemediationService.java:80,98,108`). `PromptTemplate.status` = `ACTIVE` (`infra/prompt/CompositePromptCatalog.java:26`).

### 3.3 Transições de status

**IncidentStatus** (apenas estes são escritos):

| De | Para | Gatilho | Linha |
|---|---|---|---|
| (novo) | `DETECTED` | diagnóstico novo / reabertura | `svc/AiDiagnosticService.java:157` |
| (novo) | `DETECTED` | incidente de tempestade | `svc/sre/ClusterStormService.java:146` |
| qualquer aberto | `AWAITING_HUMAN` | e-mail da mesa enviado a ≥1 destinatário | `svc/AiDiagnosticService.java:117-123` |
| qualquer aberto | `AWAITING_HUMAN` | e-mail de tempestade enviado | `svc/sre/ClusterStormService.java:102-110` |
| ≠ NORMALIZED/DISMISSED | `DISMISSED` (closedBy HUMAN, normalizedAt=now) | ação DISMISS | `svc/sre/IncidentRemediationService.java:72-76` |
| ≠ NORMALIZED/DISMISSED | `ACTION_RUNNING` (healthyStreak=0) | ação K8s aplicada com sucesso | `:92-94` |
| ∈ OPEN | `NORMALIZED` (closedBy WATCHER, normalizedAt=now, lastSeenAt=now) | `healthyStreak >= healthy-streak-required` | `svc/sre/IncidentReconciliationService.java:79-84` |

- "Aberto" para reconciliação = `DETECTED, DIAGNOSING, DIAGNOSED, NOTIFIED, AWAITING_HUMAN, ACTION_RUNNING` (`svc/sre/IncidentReconciliationService.java:30-37`).
- "Encerrado" para reabertura/remediação/storm = `NORMALIZED` ou `DISMISSED` (`svc/AiDiagnosticService.java:65`, `svc/sre/IncidentRemediationService.java:48`, `svc/sre/ClusterStormService.java:200-203`). Qualquer outro status (incl. os 12 legados e `IGNORED`) conta como aberto ao casar fingerprint.
- Não há transição saindo de `ACTION_RUNNING` exceto `NORMALIZED`/`DISMISSED`.

**PullRequestStatus**:

| Para | Gatilho | Linha |
|---|---|---|
| `OPEN` | PR criado pelo CoderAgent | `svc/agents/CoderAgentService.java:111-127` |
| `CLOSED` | PR ativo do incidente não está mais `open` no GitHub | `:54-64` |
| `CHANGES_REQUESTED` | commit de ajuste por comentário | `:167-170` |
| `AI_APPROVED` / `CHANGES_REQUESTED` | veredito do Reviewer | `svc/agents/ReviewerAgentService.java:53-58,67-71` |
| `MERGED_BY_HUMAN` | merge detectado (webhook ou varredura) | `svc/agents/DeployerAgentService.java:37-39` |
| `DEPLOYED` | rollout restart sem exceção | `:50-53` |

### 3.4 Invariantes e constantes

- `GuardianClusterConstants` (`dom/GuardianClusterConstants.java:7-24`): `CLUSTER_SERVICE_NAME="__cluster__"`, `CLUSTER_POD_NAME="cluster-outage"`, `CLUSTER_ERROR_REASON="CLUSTER_WIDE_OUTAGE"`; `clusterFingerprint(ns) = md5hex("cluster:storm:" + (ns != null ? ns.trim().toLowerCase() : "keepguard"))`; `isClusterIncident(s) = "__cluster__".equals(s)`.
- `ClusterStormAssessment.unavailablePercent()` = `total<=0 ? 0 : (unavailable*100)/total` (divisão inteira) (`app/dto/ClusterStormAssessment.java:13-18`).
- `PromptKeys` (`app/port/out/llm/PromptKeys.java:5-27`): `coder.hotfix, coder.review-adjust, reviewer.hotfix-scope, sre.investigate, github.coder-no-change, github.coder-change-applied, github.reviewer-approved, github.reviewer-rejected, github.pr-body, architecture.current-flow, architecture.proposed-flow, architecture.summary` (é a lista semeada no boot).
- `BusinessVerdict.codeDefect(reason)` (`dom/classification/BusinessVerdict.java:12-21`): reason vazio → `"defeito de código"`; `summary="Defeito de implementação de código identificado (<reason>)"`, `businessContext="A falha decorre de uma exceção não tratada na lógica da aplicação. Não há evidência de inconsistência cadastral."`, `suggestedSqlAction=""`, `requiresCodePr=true`.
- `LlmPort.LlmRequest.of(prompt, timeout, key)` → `promptVersion="classpath"`, `incidentId=null` (`app/port/out/llm/LlmPort.java:19-21`).

### 3.5 ClassificationEngine (`dom/classification/ClassificationEngine.java:12-43`, `ClassificationRule.java:19-39`)

1. `errorLower = lower(errorReason, ROOT)` ou `""`; `logsLower = lower(logs)` ou `""`.
2. Regras `enabled`, ordenadas por `priority` ASC (estável).
3. Regra casa se `containsAny(errorLower, errorContains) || containsAny(logsLower, logsContains)`; `containsAny` é falso se haystack em branco ou lista vazia; agulhas em branco ignoradas; agulha comparada com `needle.toLowerCase()` (locale default).
4. Primeira que casar → `BusinessVerdict(verdict, render(summaryTemplate), render(explanationTemplate), render(suggestedActionTemplate), requiresCodePr)`; `render` troca `{{errorReason}}` (null → "") e template null → `""`.
5. Nenhuma → `BusinessVerdict.codeDefect(errorReason)`.

Regras do classpath (`res/classification-rules.yml:1-107`), na ordem: `infra-orchestration` (10, INFRASTRUCTURE_FAULT, error: `pending, imagepullbackoff, errimagepull, containercreating, createcontainerconfigerror, oomkilled, service_outage_zero_replicas`; logs: `failed to pull and unpack image, no match for platform in manifest, connection refused, dial tcp`), `tenant-not-found` (20, DATA_INCONSISTENCY), `tenant-blocked` (30, BUSINESS_RULE_VIOLATION, error `blocked`), `insufficient-credits` (40, BUSINESS_RULE_VIOLATION), `no-carrier-route` (50, DATA_INCONSISTENCY), `code-defect-panic` (60, CODE_DEFECT, `requiresCodePr: true`). Apenas a última tem `requiresCodePr=true`. `explanationTemplate` usa bloco `|` do YAML → termina com `\n`.

---

## 4. Use cases passo a passo

Nenhum use case usa `@LogOperation` (não existe no código). Auditoria é manual via `GuardianAuditPublisher` (seção 4.18). Não há cache de leitura além de prompts. Não há métricas customizadas (só Micrometer padrão via `micrometer-registry-prometheus`, `pom.xml:37-40`). `@Transactional` aparece só em `AlertRecipientService` (`:23,52,77`), `IncidentQueryService` (classe, `readOnly`, `:29`), `IncidentInvestigationRecorder.persistInvestigation` (`:26`), `CoderAgentService.savePrState` (`:181`, auto-invocação → sem efeito [framework]) e `IncidentActionSuggestionSpringRepository.deleteByIncidentId` (`:16-18`).

### 4.1 `AiDiagnosticService.diagnosePod(ns, podName, serviceName, errorReason, forceSendEmail)` (`svc/AiDiagnosticService.java:49-143`)

Chamado por: `POST /diagnose` (via `DiagnosticUseCaseService`, `svc/DiagnosticUseCaseService.java:19-26`), watcher (seção 4.15) e consumer (4.16). Sem `@Transactional`.

1. `facts = k8sInspector.collectFacts(ns, podName, serviceName)` (seção 4.19). `recentLogs = facts.logsSnippet ?: ""` (já sanitizado).
2. `severity = evaluateSeverity(errorReason, recentLogs, facts.describe ?: "")` (`:191-204`): `describe` contém `OOMKilled` **ou** logs contêm `OutOfMemoryError` **ou** `errorReason` equalsIgnoreCase `CrashLoopBackOff` → `CRITICAL`; logs contêm `Connection refused`/`HikariPool`/`PSQLException` → `HIGH`; logs contêm `NullPointerException`/`FeignException` → `HIGH`; senão `MEDIUM`. Case-sensitive.
3. `fingerprint = md5hex(lower(trim(serviceName)) ?: "unknown" + ":" + lower(trim(errorReason)) ?: "unknown" + ":" + (IncidentSourceLocator.fingerprintLocation(recentLogs, errorReason) ?: "general"))` (`:180-189`). **Não inclui namespace.** `toLowerCase()` com locale default.
4. `existing = findFirstByFingerprintOrderByCreatedAtDesc(fp)`:
   - existe e status `NORMALIZED|DISMISSED` → novo incidente (`newOpenIncident`) com `reopenedFromId = existing.id` (`:65-69`).
   - existe e aberto → `occurrencesCount+1`, `lastSeenAt=now`, `healthyStreak=0`, `podName = facts.podName ?: existing.podName`; **salva**; se `!forceSendEmail && existing.notificationSent` → **retorna** `toDto(existing, warningEvents, false)` (sem reinvestigar) (`:70-80`). Severidade **não** é atualizada.
   - não existe → novo (`:82-85`).
5. `newOpenIncident` (`:145-163`): `occurrencesCount=1`, `lastSeenAt=now`, `capturedLogsSnippet` = últimos 3000 chars de `recentLogs` (sem marcador), `status=DETECTED`, `targetRecipientEmail = app.guardian.default-recipient`, `notificationSent=false`, `correlationId = UUID aleatório`, `healthyStreak=0`.
6. Se novo: salva; lifecycle `DETECTED` com detail `"Reaberto a partir de <id>"` ou `errorReason`; se reaberto, lifecycle `REOPENED` detail `<id antigo>`; auditoria `GUARDIAN_INCIDENT_OPENED`/`SUCCESS`/`incident.correlationId`/`INCIDENT`/`incident.id` (`:87-96`).
7. `llm = llmInvestigationService.investigate(facts, errorReason)` (4.6); `investigationRecorder.persistInvestigation(incident, facts, llm)` (4.7); salva; lifecycle `INVESTIGATED` detail `"<LLM|HEURISTIC_FALLBACK> <k8sConclusion>"` (k8sConclusion pode virar `"null"`) (`:98-103`).
8. `resultDTO = toDto(incident, warningEvents, false)`; `verdict = businessAnalyst.evaluateIncident(resultDTO, recentLogs)` → `ClassificationEngine.evaluate(catalog.activeRules(), errorReason, recentLogs)` (`svc/agents/BusinessAnalystAgentService.java:18-25`).
9. `suggestions = findByIncidentIdOrderByCreatedAtAsc(id)`.
10. `shouldDeferInfraAlert` (`:206-220`): falso se `forceSendEmail` ou já notificado; verdadeiro só se `errorReason == "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE"` **e** `facts.conclusion ∈ {NODE_FAILURE, TRANSIENT_INFRA_RECOVERABLE}` **e** `occurrencesCount < storm.infra-alert-confirm-scans` (2). Se verdadeiro: salva e retorna `resultDTO` (sem e-mail, sem PR) (`:109-116`).
11. `mesaSent = alertFanoutService.fanoutOpened(incident, suggestions)` (4.8). Se `true`: lifecycle `ALERTED` `"e-mail mesa SRE"`, `status=AWAITING_HUMAN`, `notificationSent=true`, `notificationSentAt=now`, salva, `resultDTO.notificationSent=true` (`:117-125`).
12. Se `verdict.type == INFRASTRUCTURE_FAULT || !verdict.requiresCodePr` → retorna (`:127-130`).
13. Se `serviceName` não contém `"deployment"` nem `"busybox"` (os `Optional<CoderAgent/ReviewerAgent>` estão sempre presentes): `coder.createHotfixPullRequest(resultDTO, recentLogs, verdict)` e, se houver PR, `reviewer.performReview(pr)`; qualquer exceção só é logada (`:132-140`). Roda **mesmo que o e-mail não tenha saído**.
14. Retorna `resultDTO`.

Consequência: enquanto o incidente não estiver `notificationSent=true` (cooldown, sem destinatários, falha no Rabbit), **cada varredura reexecuta 7–13**: nova evidência `CLUSTER_FACTS`, sugestões apagadas e recriadas, chamada LLM, e o pipeline de PR.

### 4.2 `DiagnosticUseCaseService` (`svc/DiagnosticUseCaseService.java:11-32`)
`diagnose` → `AiDiagnosticService.diagnosePod(...)`; `enqueue` → `IncidentEnqueuePort.enqueue(command)` (4.17).

### 4.3 `IncidentQueryService` (`svc/sre/IncidentQueryService.java:27-130`)
`@Transactional(readOnly = true)` na classe. `list` e `get` descritos em 1.3. `IncidentUseCaseService` só delega (`svc/sre/IncidentUseCaseService.java:23-43`).

### 4.4 `IncidentRemediationService.execute(...)` (`svc/sre/IncidentRemediationService.java:44-115`)

Sem `@Transactional`. Ordem:
1. Incidente inexistente → 404 `"Incidente não encontrado"` (`:46-47`).
2. Status `NORMALIZED|DISMISSED` → 409 `"Incidente já encerrado"` (`:48-50`).
3. Sugestão inexistente → 404 `"Sugestão não encontrada"` (`:51-52`).
4. `suggestion.incidentId != incidentId` → 400 `"Sugestão não pertence ao incidente"` (`:53-55`).
5. `!enabled && actionType != DISMISS` → 409 `"Opção não está habilitada: " + disabledReason` (`:56-58`).
6. `risk == DESTRUCTIVE` (só `SCALE_REPLAY`) e `confirmation` nulo ou ≠ `serviceName` (equalsIgnoreCase) → 400 `"Confirme digitando o nome do serviço: " + serviceName` (`:59-64`).
7. `before = collectFacts(ns, podName, serviceName)`; `cid = X-Correlation-ID ?: incident.correlationId`; auditoria `GUARDIAN_REMEDIATION_REQUESTED`/`SUCCESS`/cid/`INCIDENT`/id/`USER`/`actorUserId` (`:66-70`).
8. **DISMISS**: `status=DISMISSED`, `closedBy=HUMAN`, `normalizedAt=now`, salva; lifecycle `DISMISSED` detail `actorUserId`; auditoria `GUARDIAN_INCIDENT_DISMISSED`; execução `SUCCESS` com before/after = `before` (`:72-82`). Sem e-mail.
9. Demais: lock `deploy:<serviceName>`, owner `"remediation_<incidentId>_<epochMs>"`, TTL `redis.lock-ttl-seconds` (600); ocupado → 409 `"Já existe um rollout em andamento para este serviço"` (`:84-87`).
10. `apply` (`:117-140`): payload da sugestão (`payloadJson` ou `{}`); `deployment = payload.deploymentName` (não vazio) `?: facts.deploymentName`; `podName = payload.podName ?: facts.podName`; `desired = payload.desiredReplicas.asInt(1)` (ausente → 1).
    - `RECREATE_POD`: pod vazio → 409 `"Pod alvo não encontrado para recriar"`; senão `deletePod`.
    - `ROLLOUT_RESTART`: `rolling().restart()`. `ROLLBACK_REVISION`: `rolling().undo()`. `SCALE_REPLAY`: `scale(0)` e depois `scale(max(desired,1))`.
    - default → 400 `"Ação não suportada"` (inalcançável).
11. Sucesso: `after = collectFacts(...)`; `status=ACTION_RUNNING`, `healthyStreak=0`, salva; lifecycle `ACTION_APPLIED` detail `actionType.label()`; auditoria `GUARDIAN_REMEDIATION_APPLIED`; execução `SUCCESS`; `fanoutAction(incident, suggestion.label, "SUCCESS")` (`:89-101`).
12. `ResponseStatusException` dentro do try → repassada sem auditoria de falha (`:102-103`).
13. Outra exceção: auditoria `GUARDIAN_REMEDIATION_FAILED`/`FAILURE`; lifecycle `ACTION_FAILED` detail `e.getMessage()`; execução `FAILURE` (before/after = before, `errorMessage`); `fanoutAction(..., "FAILURE")`; 502 `"Falha ao aplicar ação no cluster: " + msg` (`:104-111`).
14. `finally`: libera o lock (`:112-114`).

`saveExecution` (`:142-167`): grava `beforeJson/afterJson = ObjectMapper.writeValueAsString(facts.toMap())`; se falhar (ex.: `correlation_id` > 64 chars), **tenta de novo sem** `actorEmail, actorRole, correlationId, beforeJson, afterJson`.

### 4.5 `IncidentReconciliationService.reconcileOpenIncidents()` (`svc/sre/IncidentReconciliationService.java:52-137`)

Para cada incidente em `OPEN` (seção 3.3), em `try/catch` individual:
1. Cluster (`serviceName == "__cluster__"`): `healthy = !assessClusterStorm(ns).stormActive`. Senão `healthy = collectFacts(ns, podName, serviceName).healthy` (`:63-73`).
2. Saudável: `healthyStreak+1`; lifecycle `HEALTH_CHECK_PASS` `"sequência saudável <n>/<required>"` (`required` = `app.guardian.healthy-streak-required`, default 3, `:49-50`). Se `n >= required`: `NORMALIZED`, `normalizedAt=now`, `closedBy=WATCHER`, `lastSeenAt=now`, salva; evidência `STORM_RECOVERED` (assessment) ou `NORMALIZED_FACTS` (novo `collectFacts().toMap()`), exceções ignoradas; lifecycle `NORMALIZED` `"Serviço saudável após <n> varreduras"`; auditoria `GUARDIAN_INCIDENT_NORMALIZED`; cluster → `fanoutStormNormalized` + `clearStormState(ns)`; não-cluster → `fanoutNormalized` **só se** `!clusterStormService.isStormActive(ns)` (`:75-123`). Se ainda não atingiu: salva (`:125-126`).
3. Não saudável: se `healthyStreak > 0` **ou** status `ACTION_RUNNING` → lifecycle `HEALTH_CHECK_FAIL` (`"tempestade ainda ativa"` / `"serviço ainda indisponível"`); `healthyStreak=0`, `lastSeenAt=now`, salva (`:128-136`).

Gera uma linha de lifecycle por incidente aberto saudável a cada varredura, e uma por varredura para `ACTION_RUNNING` que segue indisponível.

### 4.6 `LlmInvestigationService.investigate(facts, errorReason)` (`svc/sre/LlmInvestigationService.java:32-151`)

1. `heuristic = heuristic(facts, errorReason)` (`:86-113`):
   - `rootCause = pt.errorReason(errorReason) + " — conclusão K8s: " + pt.k8sConclusion(facts.conclusion)`.
   - `summary = "O controlador " + (healthy ? "parece saudável" : "não restabeleceu o serviço") + ". Réplicas desejadas: " + desired + "; disponíveis: " + available + "; motivo de espera: " + pt.waitingReason(waiting) + "."` (`Integer` nulo vira `"null"`).
   - `riskNotes = "Ações mutativas só após confirmação humana na mesa SRE."`.
   - `recommended`: `NODE_FAILURE|TRANSIENT_INFRA_RECOVERABLE` → `RECREATE_POD`; demais conclusões → `DISMISS`; e se `desired>0 && (available null|0) && !crashLoop && !imagePull` → `+ROLLOUT_RESTART`.
2. `!llmPort.available()` → heurística com `heuristicFallback=true`.
3. Senão: `snap = prompts.snapshot("sre.investigate")`; variáveis (`:40-65`): `errorReason, errorReasonLabel, namespace, serviceName, podName, deploymentName, desiredReplicas, availableReplicas, readyReplicas, replicaSetCount, phase, phaseLabel, waitingReason, waitingReasonLabel, terminatedReason, terminatedReasonLabel (usa waitingReason()), restartCount, crashLoop, imagePullFailure, replicasIntentionallyZero, conclusion (ou "UNKNOWN"), conclusionLabel, warningEvents (List.toString "[a, b]"), logsSnippet = tail(PiiLogSanitizer.sanitize(logs), 1200)`. Strings nulas → `""`; inteiros via `String.valueOf` (`"null"`).
4. `prompt = NARRATIVE_LANGUAGE_RULE + "\n" + render(...)`; `complete(LlmRequest(prompt, llm.timeout-seconds, snap.key, snap.version, incidentId = null))` (`:66-69`) — **incidentId não é passado**.
5. Resposta presente → `parse(raw)`: recorta do primeiro `{` ao último `}`; lê `rootCause/summary/riskNotes` (vazio → valor da heurística) e `recommendedActionIds` (array → `asText`; vazio → lista da heurística); **depois** força `heuristicFallback=false` — mesmo quando o parse falhou e devolveu a heurística (`:70-74,129-133`).
6. Vazia → heurística `heuristicFallback=true`. Exceção → idem.

### 4.7 `IncidentInvestigationRecorder.persistInvestigation` (`svc/sre/IncidentInvestigationRecorder.java:26-59`, `@Transactional`)
1. Evidência `CLUSTER_FACTS` = JSON de `facts.toMap()` (falha só loga).
2. Muta o incidente (não salva): `k8sConclusion = conclusion.name()|null`, `investigationSource = heuristicFallback ? HEURISTIC_FALLBACK : LLM`, `aiRootCauseAnalysis = rootCause`, `aiSummary = summary`, `aiRecommendedAction = riskNotes`.
3. `deleteByIncidentId` + insere as 5 sugestões de `ActionCatalogPolicy.build` na ordem RECREATE_POD, ROLLOUT_RESTART, ROLLBACK_REVISION, SCALE_REPLAY, DISMISS.

`ClusterFacts.toMap()` (`app/dto/ClusterFacts.java:38-62`) — `LinkedHashMap` nesta ordem: `namespace, serviceName, podName, deploymentName, desiredReplicas, availableReplicas, readyReplicas, replicaSetCount, phase, waitingReason, terminatedReason, restartCount, exitCode, replicasIntentionallyZero, crashLoop, imagePullFailure, warningEvents, logsSnippet, describe, conclusion (name|null), healthy`. Nulos serializados.

### 4.8 `ActionCatalogPolicy.build(facts, llm)` (`svc/sre/ActionCatalogPolicy.java:29-151`)

`recommended = set(llm.recommendedActionIds)`. `outage = desired != null && desired > 0 && (available == null || available == 0)`. Para cada ação, o **primeiro** motivo verdadeiro desabilita; `enabled = disabledReason == null`; `aiRationale = recommended.contains(name) ? "A IA ranqueou esta opção com base nos fatos do cluster." : "Opção do catálogo avaliada pela política de SRE."`; `label = actionType.label()`.

| Ação | Risco | Condições de desabilitação (mensagem exata) | Linhas |
|---|---|---|---|
| RECREATE_POD | LOW | crashLoop → `"O container está em loop de reinício: o ReplicaSet já reinicia o processo; recriar o pod não corrige a causa."`; imagePull → `"Falha de imagem ou configuração; recriar o pod não resolve o pull no registry."`; zero → `"Deployment com réplicas zeradas de propósito."`; não (`NODE_FAILURE` ou `TRANSIENT_INFRA_RECOVERABLE` ou phase equalsIgnoreCase `Unknown`) → `"Conclusão K8s (" + pt.k8sConclusion(conclusion) + ") não indica pod órfão ou falha de nó."` | `:42-62` |
| ROLLOUT_RESTART | HIGH | crash → `"O container está em loop de reinício: um restart cego mascara o defeito."`; image → `"Falha de imagem ou configuração: o rollout não puxa uma imagem inválida."`; zero → `"Réplicas zeradas de propósito; use scale apenas com confirmação destrutiva se for o caso."`; !outage → `"O deployment não está com zero réplicas disponíveis e desejadas maiores que zero."` | `:64-83` |
| ROLLBACK_REVISION | HIGH | `replicaSetCount` nulo ou ≤1 → `"Não há ReplicaSet anterior para rollback."`; !outage → `"Sem indisponibilidade de réplicas; rollback não é a primeira opção."` | `:85-99` |
| SCALE_REPLAY | DESTRUCTIVE | zero → `"Réplicas zeradas parecem intencionais; replay de escala desabilitado."`; crash ou image → `"Sintoma de loop de reinício ou imagem: zerar e subir não corrige a causa."`; conclusão ≠ TRANSIENT_INFRA_RECOVERABLE ou !outage → `"Replay de escala só quando a conclusão é infraestrutura transitória recuperável com réplicas desejadas maiores que zero."` | `:101-119` |
| DISMISS | LOW | nunca desabilitado; payload `"{}"` | `:121-123` |

Payload das 4 primeiras (`:134-147`), montado por concatenação: `{"podName":"<pod|''>","deploymentName":"<deploymentName ?: serviceName>","desiredReplicas":<desired ?: 1>}`; escape só de `\` e `"` (`null` → `""`).

### 4.9 `AlertRecipientService` / `AlertRecipientUseCaseService`

`AlertRecipientService` (`svc/sre/AlertRecipientService.java:16-95`):
- `EMAIL = ^[^@\s]+@[^@\s]+\.[^@\s]+$` (`:18`); `normalize = trim + lower(ROOT)`, null → `""` (`:92-94`).
- `listEnabledOrSeed()` (`@Transactional`, `:23-45`): habilitados por `createdAt ASC`; se vazio e `count()==0` e há `default-recipient` → insere `{email: normalize(default), enabled: true, label: "default"}` e relista; se vazio e há default → retorna lista em memória `{email default, label "fallback"}` (não persistida, `id` nulo); senão `[]`.
- `listAll()` (`:47-50`): `listEnabledOrSeed()` (auto-invocação, sem tx) e `findAllByOrderByCreatedAtAsc`.
- `upsert(email, label, enabled)` (`:52-75`): regex falha → `IAE("E-mail inválido")`; existe (`findByEmailIgnoreCase`) → `enabled` sobrescrito, `label` só se não nulo, salva (sem checar limite); novo e `enabled` e `countByEnabledTrue() >= max-alert-recipients` (20) → `IAE("Limite de destinatários ativos atingido")`; senão insere.
- `setEnabled(id, enabled)` (`:77-86`): inexistente → `IAE("Destinatário não encontrado")`; habilitando um desabilitado com limite atingido → `IAE("Limite de destinatários ativos atingido")`.

`AlertRecipientUseCaseService` (`svc/sre/AlertRecipientUseCaseService.java:23-55`): após upsert/patch publica auditoria `GUARDIAN_ALERT_RECIPIENT_UPSERTED` / `GUARDIAN_ALERT_RECIPIENT_PATCHED`, `SUCCESS`, `correlationId = MDC["correlationId"]`, `ALERT_RECIPIENT`, `id|""`, `actorType = MDC["codeUser"] != blank ? "USER" : "SYSTEM"`, `codeUser`. Como nada preenche o MDC, sai sempre `SYSTEM` e `correlationId` aleatório.

### 4.10 `AlertFanoutService` (`svc/sre/AlertFanoutService.java:24-183`)

Destinatários = `recipientService.listEnabledOrSeed()`. Para cada um: cooldown `alertCooldownPort.tryAcquire(scope, anti-flapping-cooldown-minutes=15)`; se não adquiriu, **pula sem gravar entrega**; senão `emailNotificationService.sendHtmlTo(email, subject, html, serviceName, logContext=kind, correlationId=incident.id, publishAudit=true)` e grava `IncidentAlertDelivery{incidentId, email, outcome SENT|FAILED, kind, correlationId = incident.correlationId}`. Retorna `true` se algum envio publicou.

| Método | kind | scope do cooldown | Subject | Linhas |
|---|---|---|---|---|
| `fanoutOpened` | `OPENED` | `"opened:<serviceName>:<email lower>"` | `"[KeepGuard Guardian] <svc> — " + (k8sConclusion != null ? pt.k8sConclusion(k8s) : pt.errorReason(errorReason))` | `:32-53,143-166` |
| `fanoutNormalized` | `NORMALIZED` | `"normalized:<svc>:<email>"` | `"[KeepGuard Guardian] Normalizado: <svc>"` | `:55-65` |
| `fanoutAction` | `ACTION` | `"action:<svc>:<email>"` | `"[KeepGuard Guardian] Ação <label> — <svc>"` | `:67-77` |
| `fanoutStormOpened` | `STORM_OPENED` | `"storm:opened:<incidentId>:<email>"` | `"[KeepGuard Guardian] Tempestade no cluster — <unavailable> serviços afetados"` | `:79-102,118-141` |
| `fanoutStormNormalized` | `STORM_NORMALIZED` | `"storm:normalized:<incidentId>:<email>"` | `"[KeepGuard Guardian] Cluster normalizado — tempestade encerrada"` | `:104-116` |

Corpo = template `mesa.html` via `mesaHtml(title, incident, body, extra, ctaLabel, ctaUrl)` (`:168-179`) com variáveis `title, serviceName, podName (n())`, `k8sConclusion = pt.k8sConclusion(incident.k8sConclusion)`, `body`/`extra` com `\n → <br/>` (sem escape HTML), `ctaUrl = console-url + "?tab=guardian"`, `ctaLabel`. `n(null) = "—"`.

| Método | title | body | extra | ctaLabel |
|---|---|---|---|---|
| Opened | `"Incidente aberto: <svc>"` | `aiSummary ?: aiRootCauseAnalysis` | `"Opções habilitadas:<br/>" + join("<br/>", label + " (risco " + risk.label() + ")")` ou `"Nenhuma ação habilitada pela política — abrir a mesa para detalhes."` | `"Abrir mesa SRE"` |
| Normalized | `"Serviço normalizado: <svc>"` | `"O watcher confirmou saúde em <streak> varreduras consecutivas."` | `aiSummary ?: ""` | `"Ver incidente"` |
| Action | `"Ação <label>: <outcome>"` | `"Uma ação humana foi aplicada no cluster."` | `"Resultado: <outcome>"` | `"Ver incidente"` |
| StormOpened | `"Tempestade no cluster: múltiplos serviços indisponíveis"` | `"Tempestade de infraestrutura detectada no namespace <strong>NS</strong>.<br/><br/><strong>Motivo:</strong> <pt.stormReason><br/><strong>Deployments indisponíveis:</strong> U / T (P%)<br/><br/><strong>Serviços afetados:</strong><br/>" + join("<br/>", "• "+s) ou "—"` | `"Os alertas individuais foram suprimidos para evitar spam. Acompanhe a mesa SRE."` | `"Abrir mesa SRE"` |
| StormNormalized | `"Cluster normalizado após tempestade"` | `"O cluster voltou ao normal após <streak> varreduras saudáveis consecutivas."` | `aiSummary ?: ""` | `"Ver incidente"` |

### 4.11 `ClusterStormService` (`svc/sre/ClusterStormService.java:31-204`)

`handleWatcherScan(ns)` (`:46-61`): `assessment = assessClusterStorm(ns, storm)`; `state = stormStatePort.get(ns)`. Tempestade ativa → `handleActiveStorm` e retorna **true** (inclusive antes de confirmar). Senão, se havia estado → log + `clear(ns)`; retorna false.

`handleActiveStorm` (`:72-124`):
1. Estado anterior ou novo; seta `namespace, nodeNotReady, affectedServices = unavailableServiceNames`; `confirmStreak = prior+1` ou 1; `startedAtEpochMs = now` se ≤ 0.
2. `confirmRequired = max(1, infra-alert-confirm-scans)`; `confirmed = nodeNotReady || confirmStreak >= confirmRequired`. Não confirmado → salva estado (TTL `state-ttl-seconds`) e retorna.
3. `incident = resolveOpenClusterIncident` (`:126-167`): por `state.incidentId` se aberto → `updateClusterIncident`; senão por `clusterFingerprint(ns)` se aberto → update; senão cria: `namespace, serviceName="__cluster__", podName="cluster-outage", errorReason="CLUSTER_WIDE_OUTAGE", severity=HIGH, status=DETECTED, fingerprint, occurrencesCount=1, lastSeenAt=now, k8sConclusion = nodeNotReady ? "NODE_FAILURE" : "TRANSIENT_INFRA_RECOVERABLE", aiSummary = buildStormSummary, aiRootCauseAnalysis = "Tempestade de infraestrutura: " + pt.stormReason(reason), aiRecommendedAction = "Aguardar recuperação do cluster. Verificar saúde do nó e kubelet.", targetRecipientEmail = default, notificationSent=false, correlationId = UUID, healthyStreak=0`; salva; lifecycle `DETECTED` detail `pt.stormReason`; auditoria `GUARDIAN_CLUSTER_STORM_OPENED`; evidência `STORM_ASSESSMENT`.
   - `updateClusterIncident` (`:169-175`): `lastSeenAt=now`, `aiSummary`, `k8sConclusion`, evidência `STORM_ASSESSMENT` (**uma por varredura**), salva.
4. `state.incidentId = incident.id`; salva estado.
5. `!notificationSent` → `fanoutStormOpened`; se enviado: `notificationSent=true`, `notificationSentAt=now`, `status=AWAITING_HUMAN`, salva, lifecycle `ALERTED` `"e-mail tempestade cluster"`. Já notificado → `occurrencesCount+1`, `lastSeenAt=now`, `healthyStreak=0`, salva.

`buildStormSummary` (`:189-198`): `"<U> de <T> deployments indisponíveis (<P>%). Motivo: <pt.stormReason>" + (sample vazio ? "" : ". Ex.: " + sample)`; `sample` = primeiros 8 nomes com `", "` + `"… (+N)"` se > 8.

`isStormActive(ns)` = estado existe **ou** `assessClusterStorm(...).stormActive` (`:63-66`). `clearStormState(ns)` (`:68-70`).

Evidência `STORM_*` = JSON do record `ClusterStormAssessment`: `{"nodeNotReady","totalDeployments","unavailableDeployments","unavailableServiceNames","stormActive","stormReason"}` (sem `unavailablePercent`, que não é getter) [framework].

### 4.12 `CoderAgentService` (`svc/agents/CoderAgentService.java:31-336`)

`createHotfixPullRequest(incident, rawStackTrace, verdict)` (`:44-138`):
1. `repoName = incident.serviceName`; `base = "main"`.
2. PRs ativos do (repo, incidentId) (`findByRepoNameAndIncidentIdAndStatusIn`); para cada: `getPullRequestStatus` (erro → `state "open"`); `open` → retorna esse PR; senão `status=CLOSED` e salva (`:51-64`).
3. `branch = "fix/guardian-" + repo + "-" + UUID.random()[0:8]`.
4. Dentro de `try` (qualquer exceção → log, `Optional.empty()`): `baseSha = getBranchSha(repo, "main")`; `createBranch` (false → empty) — **antes** de resolver arquivo.
5. `SourceFileResolver.resolve(repo, "main", rawStackTrace, errorReason)` (4.14); vazio → empty.
6. `fixedCode = generateCodeFixWithAi(...)` (`:186-227`): LLM indisponível → código atual. Senão `slice = ScopedSourcePatcher.extract(code, path, errorReason, rootCause, line)`; `language` = `"Golang"` (go), `"Java"` (java), `"a linguagem do arquivo"`; `scopeHint` = `"o menor trecho possível do arquivo"` (whole file) ou `"SOMENTE a função/método extraído abaixo (não a classe/arquivo inteiro)"`; render `coder.hotfix` com `language, serviceName, filePath, errorReason, rootCause, incidentLine (ou "desconhecida"), scopeHint, functionSource, stackTrace = tail(logs, 1200)`; `complete(prompt, codegen-timeout-seconds, key, version, incidentId)`; vazio → atual; senão `ScopedSourcePatcher.applyReplacement(slice, out)`. Exceção → atual.
7. `fixedCode == currentCode` → log `"Patch idêntico à main — PR não será aberto."` e empty (branch fica).
8. `qa = QaAutomation.certifyQuality(repo, path, current, fixed, errorReason + " " + rootCause)`; `arch = Architect.designSolution(incident, repo, path, logs)`.
9. Commit: mensagem `"fix(<repo>): correção automatizada por KeepGuard AI Guardian\n\nCausa: " + rootCause`; falha → empty.
10. PR: título `"🚨 [AI Guardian Hotfix] Correção de Incidente: " + errorReason`; corpo `github.pr-body` (abaixo); `prNumber = (int) result.prNumber`, `prUrl = result.htmlUrl`.
11. Salva `PullRequestLifecycle{incidentId, repo, branch, base, filePath, prNumber, prUrl, status OPEN, flags false}`; `sendPrOpenedEmail` (falha só loga). Retorna o PR.

Corpo do PR (`:272-324`) — variáveis de `github.pr-body`: `serviceName, podName (nvl→""), severity = pt.severity, errorReason = pt.errorReason, filePath, rootCause, action (= recommendedAction), businessSection, archSection, qaSection, approverGithub`. Seções (texto exato após remoção de indentação do text block):
- business: `"### 👔 Análise de Negócio & Dados (BusinessAnalystAgent)\n- **Diagnóstico Funcional:** <summary>\n- **Impacto no Domínio:** <businessContext>\n"`.
- arch: `"### 📐 Análise de Arquitetura & Sequência (SoftwareArchitectAgent)\n- **Padrão Arquitetural:** \`<pattern>\`\n<summary>\n\n#### 🔴 Fluxo Atual com Falha (Antes)\n<currentFlow>\n\n#### 🟢 Fluxo Proposto Corrigido (Depois)\n<proposedFlow>\n"`.
- qa: `"### 🧪 Certificação de Qualidade (QaAutomationAgent)\n**Status Geral:** \`<verdictText>\`\n\n<tabela>\n"`.

`applyReviewFeedbackAndNotify(repo, pr, commentId, feedback, author)` (`:140-179`):
1. PR não cadastrado → false.
2. Lê arquivo da branch (`content` ou `""`, `sha`). `adjusted = generateIterativeAdjustmentWithAi(content, feedback)` (`:229-246`): se LLM disponível, render `coder.review-adjust` (`feedback, currentCode`), `complete(..., codegen timeout, incidentId null)`; se veio e ≠ atual → remove `` ```[a-z]*\n? `` e `` ``` `` e `trim()`. Senão/erro → `applyHeuristicReviewDirectives` (`:248-270`):
   - feedback vazio → atual. `fb = lower(feedback)`.
   - Se contém `coment`/`comentário` **e** algum de `remov, tirar, apagar, deletar, sem, limp, nao, não`: remove linhas só-comentário `(?m)^[ \t]*//.*\R?`, comentários de fim de linha `(?m)[ \t]+//.*$`, blocos `/\*(?s:.*?)\*/`; mudou → `trim() + "\n"`.
   - Se contém `linha` e (`remov|apagar|deletar`): remove só linhas-comentário; mudou → `trim()+"\n"`.
   - Senão atual.
3. Sem mudança → resposta `github.coder-no-change` (`commentFeedback`), `replyToPrReviewComment`, e-mail `comment-replied` (generatedCommit=false), retorna false.
4. Commit `"fix(review): ajuste solicitado por @<author>\n\n<feedback>"` na branch do PR com sha; falha → false.
5. `status=CHANGES_REQUESTED`, `aiReviewed=false`, `aiApproved=false`, salva; resposta `github.coder-change-applied` (`commentFeedback, branch, approverGithub`); reply; e-mail (generatedCommit=true); retorna true.

### 4.13 `HandlePrEventUseCase` (`svc/pr/HandlePrEventUseCase.java:22-110`)

- `onPullRequest` (`:35-46`): auditoria `GUARDIAN_GITHUB_WEBHOOK`/`SUCCESS`/cid null/`PR`/`"<repo>#<n>"`; `closed` + merged → `deployer.handleMergedPullRequest(repo, n, sender)`; `opened`/`reopened` → se houver PR cadastrado, `reviewer.performReview`.
- `onComment` (`:49-68`): `isBotComment(author, body)` (author contém `"bot"` ou body contém `"[CoderAgent]"`/`"[ReviewerAgent]"`, `:105-109`) → ignora sem auditoria; senão auditoria; `commentId` já processado → retorna; senão grava `ProcessedComment{commentId, prNumber}`; `coder.applyReviewFeedbackAndNotify`; se ajustou → `reviewer.performReview`.
- `scanOpenPullRequests` (`:71-90`): PRs `active()` com `prNumber`; `getPullRequestStatus`; `merged && !mergedByHuman` → `deployer.handleMergedPullRequest(repo, n, mergedBy ?: approverGithub)`; senão `getPrReviewComments` (1ª página) e `onComment` para cada — **auditoria é publicada antes do teste de "já processado"**, então cada comentário humano gera um evento de auditoria a cada 45 s.
- `beginDelivery` (`:93-98`): ver 1.4.

### 4.14 Agentes auxiliares

- **ReviewerAgentService.performReview(pr)** (`svc/agents/ReviewerAgentService.java:37-77`): lê arquivo da branch; escopo = incidente (`errorReason`, `aiRootCauseAnalysis`; nulo/vazio → `"—"`) ou `("hotfix deste PR", "correção automatizada pelo CoderAgent")` (`:79-86`). Veredito (`:88-111`): LLM indisponível ou exceção → aprovado com `"VEREDITO: APROVADO\nLLM indisponível; hotfix aceito no escopo do incidente."`; senão prompt `NARRATIVE_LANGUAGE_RULE + "\n" + render(reviewer.hotfix-scope, {serviceName, filePath, errorReason = pt.errorReason(scope), rootCause, code = tail(code, 8000)})`, `complete(timeout-seconds, incidentId)`; vazio → `"VEREDITO: APROVADO\nHotfix revisado com timeout/fallback do LLM."`. `parseApproved` (`:113-119`): nulo/vazio → true; senão junta as 12 primeiras linhas (cada uma prefixada por `"\n"`), upper, aprovado se **não** contém `"VEREDITO: REPROVADO"`. Aprovado → `submitReview(event "COMMENT", github.reviewer-approved{feedback, approverGithub})`; `isFirstApproval = !aiApproved && status != CHANGES_REQUESTED`; `aiReviewed=aiApproved=true`, feedback, `AI_APPROVED`, salva; e-mail `pr-ready-for-approval` só na primeira aprovação. Reprovado → `submitReview("COMMENT", github.reviewer-rejected{feedback})`, `aiApproved=false`, `CHANGES_REQUESTED`. Exceção → false.
- **DeployerAgentService.handleMergedPullRequest(repo, n, mergedBy)** (`svc/agents/DeployerAgentService.java:25-62`): busca PR; inexistente → cria `{repo, n, branch "main", base "main", MERGED_BY_HUMAN}`; `mergedByHuman=true`, `MERGED_BY_HUMAN`, salva; lock `deploy:<repo>` owner `"deploy_pr_<n>_<epochMs>"` TTL 600 — ocupado → false (e não há retry: PR já não é ativo); e-mail `deploy-started`; `KubernetesOpsPort.rolloutRestart(app.guardian.namespace, repo)`; `deployedToK8s=true`, `DEPLOYED`, salva; e-mail `deploy-completed`; exceção → false; libera lock.
- **QaAutomationAgentService.certifyQuality(svc, file, original, fixed, reason)** (`svc/agents/QaAutomationAgentService.java:28-159`): `reason = lower(reason)`; `changed = original != null && fixed != null && !equals`. TC-01 conforme o primeiro que casar: aritmético (`divis, zero, arithmetic, overflow, nan, tarif, rate`) → nome `"TC-01: Guarda contra divisão / valor inválido"`, desc `"O fluxo do incidente trata denominador/rate inválido"`, passa se `fixed` contém `<= 0|== 0|< 1|<=0|==0`; nulo (`null, npe, nil, pointer`) → `"TC-01: Guarda contra nulo"` / `"O fluxo do incidente valida ponteiro/objeto ausente"` / contém `!= null|== null|!= nil|== nil|Optional`; limites (`index, bounds, slice, array, range, underflow`) → `"TC-01: Guarda de limites"` / `"O fluxo do incidente valida índice/tamanho"` / contém `len(|.length|size()| < 0|>= len|>= length`; senão `"TC-01: Alteração no fluxo do incidente"` / `"Há diferença no arquivo alvo para o motivo do incidente"` / `changed`. Esperado `"Hotfix cobre o erro reportado"`. TC-02 `"TC-02: Patch pontual (não reescreve a classe)"` / `"Maior parte das linhas originais permanece"` / `"Diff isolado ao fluxo do incidente"`: original nulo → OUT_OF_SCOPE; senão PASSED se `isSurgical && changed` (linhas não-brancas do original presentes no novo ≥ 55%, split por `\R`). TC-03 `"TC-03: Contratos e fluxos fora do incidente"` / `"Demais métodos / cenários do arquivo"` / `"Preservados (observação)"` / OUT_OF_SCOPE. `certified` = todos não-OUT_OF_SCOPE PASSED; `verdictText` = `"QA DO HOTFIX: APROVADO NO ESCOPO DO INCIDENTE (demais casos = observação)"` ou `"QA DO HOTFIX: REPROVADO — o incidente não foi coberto pelos cheques in-scope"`. Tabela: cabeçalho `"| Caso de Teste | Descrição | Resultado Esperado | Status |\n| :--- | :--- | :--- | :---: |\n"` e linhas `` "| `<nome>` | <desc> | <esperado> | **<ícone>** |\n" `` com ícones `✅ PASSOU`, `❌ FALHOU`, `⏭️ FORA DO ESCOPO`.
- **SoftwareArchitectAgentService.designSolution** (`svc/agents/SoftwareArchitectAgentService.java:19-40`): vars `serviceName ?: "app"`, `file` (vazio → `"camada de aplicação"`), `error = escapeMermaid(errorReason ?: "falha no fluxo")` (`"`→`'`, `\n`→espaço, `<`→`&lt;`); `pattern = "Hotfix pontual no fluxo do incidente (sem refactor da classe)"`; `summary/current/proposed` = render de `architecture.summary/current-flow/proposed-flow`.
- **SourceFileResolver.resolve(repo, branch, logs, reason)** (`svc/agents/SourceFileResolver.java:23-67`): `hint = IncidentSourceLocator.parse(logs, reason)`; `tree = listSourceFilePaths`; `ranked = rankPaths(tree, hint, reason)`; se vazio e há basenames no hint → todos os paths cujo basename = algum basename do hint (case-insensitive). Para cada ranqueado: lê; pula sem conteúdo; `line = hint.line` só se o basename do path = `primaryBasename` e `lineExistsIn(content, line)`; retorna o primeiro.

### 4.15 `KubernetesHealthWatcherScheduler` (`in/scheduler/KubernetesHealthWatcherScheduler.java:20-90`)

- `scanPullRequestInteractions`: `@Scheduled(fixedDelay = 45000, initialDelay = 10000)` → `scanOpenPullRequests()`, erros só logados (`:29-36`). Roda **independente de `watcher-enabled`**.
- `scanClusterHealth`: `@Scheduled(fixedDelayString = "${app.guardian.scan-interval-ms:60000}", initialDelay = 30000)` (`:38-89`):
  1. `watcher-enabled=false` → sai.
  2. `ns = app.guardian.namespace`. `clusterStormService.handleWatcherScan(ns)` true → `reconcileOpenIncidents()` e sai.
  3. Para cada pod de `listUnhealthyPods(ns)`: `serviceName` = label `app` ou nome do pod; `errorReason` = `"RESTART_OR_FAILURE_DETECTED"`, sobrescrito pela `phase` quando não nula (na prática sempre: `Running|Pending|Failed|Unknown`); logs = `getPodLogs(ns, pod, 80)` **não sanitizado**: contém `CODE_DEFECT_` → substring do índice até o `\n` seguinte (`trim`), ou até 50 chars se não houver `\n`; senão `PANIC RECOVER`/`PANIC_RUNTIME` → `"PANIC_RUNTIME_EXCEPTION"`; senão `NullPointerException` → `"NullPointerException"`. `diagnosePod(ns, pod, svc, reason, false)`.
  4. Para cada deployment de `listDeploymentsWithZeroReplicas(ns)` (desired>0 e available=0, **ou desired==0**): `diagnosePod(ns, "<dep>-deployment", dep, "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE", false)`.
  5. `reconcileOpenIncidents()`.
  6. Todo o passo 2–5 está num único `try`: uma exceção em qualquer `diagnosePod` aborta o resto da varredura e a reconciliação.
- Com `spring.threads.virtual.enabled: true` (`res/application.yml:5-7`) o Boot usa `SimpleAsyncTaskScheduler` em virtual threads → as duas tarefas podem rodar em paralelo [framework].

### 4.16 `IncidentQueueConsumer` (`in/messaging/IncidentQueueConsumer.java:15-45`)

`@RabbitListener(queues = "guardian.incident.process.queue")` recebe `IncidentQueueMessage` (`infra/messaging/dto/IncidentQueueMessage.java:15-23`: `trackingId: UUID, namespace, podName, serviceName, errorReason: String, forceSendEmail: boolean, enqueuedTimestamp: long`). Loga latência `now - enqueuedTimestamp`; `rateLimiter.acquireAiPromptPermit()` (bucket LLM); `diagnosePod(...)`; exceção → relança.

Container (`infra/messaging/RabbitMqTopologyConfig.java:65-93`): `SimpleRabbitListenerContainerFactory` criado à mão (as props `spring.rabbitmq.listener.*` não se aplicam), conversor `Jackson2JsonMessageConverter` (tipo inferido do parâmetro [framework]), retry stateless `SimpleRetryPolicy(3)` (3 tentativas no total) com backoff exponencial 1000 ms ×2, máx 5000 ms; ao esgotar, `RepublishMessageRecoverer` → exchange `guardian.incident.exchange`, RK `guardian.incident.process.dlq.rk` (adiciona headers `x-exception-message`, `x-exception-stacktrace`, `x-original-exchange`, `x-original-routingKey` [framework]) e a mensagem é confirmada. Ack AUTO, prefetch/concorrência default (250/1) [framework]. `diagnosePod` não é idempotente: cada tentativa repete efeitos parciais.

### 4.17 Publicação na fila (`infra/messaging/IncidentEnqueueAdapter.java:20-43`)
`convertAndSend("guardian.incident.exchange", "guardian.incident.process", IncidentQueueMessage{trackingId=random, ..., enqueuedTimestamp=now})` com o `RabbitTemplate` de `infra/config/RabbitMQConfig.java:27-32` (Jackson2Json → header `__TypeId__ = com.keepguard.ms_ai_guardian.infrastructure.messaging.dto.IncidentQueueMessage`, `content_type=application/json`, delivery PERSISTENT [framework]).

### 4.18 Auditoria — `GuardianAuditPublisher` (`out/audit/GuardianAuditPublisher.java:22-111`)

`keepguard.audit.enabled=false` → no-op. Monta `HashMap` (ordem não garantida): `eventId` (UUID), `occurredAt` (`Instant.now().toString()`), `schemaVersion: 1`, `sourceService: "ms-ai-guardian"`, `correlationId` (vazio → UUID novo), `tenantId`/`companyId` só se presentes no MDC (nunca), `action`, `outcome`, `actor: {type, codeUser?}` (`type` vazio → `SYSTEM`; se há `codeUser` e type `SYSTEM` → `USER`), `resource: {type, id ?: ""}`. Publica **assíncrono** (`CompletableFuture.runAsync`) após declarar uma vez a exchange (`topic`, durável) em `keepguard.audit.exchange`, RK `keepguard.audit.routing-key`; header `X-Correlation-ID`, `PERSISTENT`; falha só loga.

Ações publicadas: `GUARDIAN_INCIDENT_OPENED` (`svc/AiDiagnosticService.java:94`), `GUARDIAN_CLUSTER_STORM_OPENED` (`svc/sre/ClusterStormService.java:163`), `GUARDIAN_INCIDENT_NORMALIZED` (`svc/sre/IncidentReconciliationService.java:110`), `GUARDIAN_REMEDIATION_REQUESTED|APPLIED|FAILED`, `GUARDIAN_INCIDENT_DISMISSED` (`svc/sre/IncidentRemediationService.java:69,78,96,105`), `GUARDIAN_ALERT_SENT|FAILED` (resource `INCIDENT`, id = **serviceName**) (`out/notification/EmailNotificationService.java:61-66,256-261`), `GUARDIAN_ALERT_RECIPIENT_UPSERTED|PATCHED` (4.9), `GUARDIAN_GITHUB_WEBHOOK` (4.13).

### 4.19 Inspeção K8s — `KubernetesInspectorService` (`out/k8s/KubernetesInspectorService.java:24-484`)

- `listUnhealthyPods(ns)` (`:37-46`) → pods com `isPodUnhealthy` (`:163-211`): `Succeeded` → não; `Failed|Unknown` → sim; container `waiting.reason` ∈ `CrashLoopBackOff, ImagePullBackOff, ErrImagePull, CreateContainerConfigError` → sim; `state.terminated.exitCode != 0` → sim; `Running` e app não está em `SKIP_LOG_SCAN_APPS = postgres, redis, rabbitmq, ollama, prometheus, grafana, minio, ms-ai-guardian` (e nome não começa com `ms-ai-guardian`) → lê 80 linhas de log e marca se contém `PANIC RECOVER|NullPointerException|BadSqlGrammarException|CODE_DEFECT_|PANIC_RUNTIME`. `Pending` não é anômalo.
- `collectFacts(ns, pod, svc)` (`:288-378`): deployment por nome = svc, senão primeiro com label `app=svc`; `deploymentName` = nome do deployment ou svc; `desired` = `spec.replicas` (nulo → 1, sem deployment → 1); `available/ready` (nulo → 0; sem deployment → 0/0); `replicasZero = desired==0`; `replicaSetCount` = RS com ownerReference de nome = deploymentName (lista todos os RS do ns). Pod: nome exato se não vazio e não termina em `-deployment`, senão primeiro com `app=svc`. Por container (o último sobrescreve `waiting`/`terminated`): `restartCount = max`; `waiting` CrashLoopBackOff → `crashLoop`; ImagePullBackOff/ErrImagePull/CreateContainerConfigError → `imagePull`; `lastState.terminated` → `terminated` e `exitCode`; `OOMKilled` → `crashLoop=true`. `events` = `getRecentWarningEvents` (todos os eventos do ns filtrados por `Warning` e `involvedObject.name == pod`, formato `"[<lastTimestamp|Agora>] <reason>: <message> (Count: <count|1>)"`, `:236-251`). `describe` = `describePodHealth` (`:253-286`) ou `"Pod não encontrado"`. Logs = `PiiLogSanitizer.sanitize(getPodLogs(ns, pod, 80))`, `getPodLogs` = `tail(log, 4000)` ou `"Logs não disponíveis: " + msg` em erro (`:224-234`).
- `classify` (`:457-483`), primeira regra verdadeira: sem deployment → `NO_CONTROLLER`; `replicasZero` → `REPLICAS_INTENTIONALLY_ZERO`; imagePull/waiting de imagem → `IMAGE_OR_CONFIG`; phase `Pending` ou eventos contêm `FailedScheduling` → `UNSCHEDULABLE`; phase `Unknown` ou eventos contêm `Evicted`/`NodeNotReady` → `NODE_FAILURE`; crashLoop → `CONTROLLER_ALREADY_RETRYING`; `desired>0 && available 0` → `TRANSIENT_INFRA_RECOVERABLE`; senão `CONTROLLER_ALREADY_RETRYING`.
- `healthy = desired>0 && available>0 && !crashLoop && !imagePull && !(algum pod app=svc com isPodUnhealthy)` (`:348-353`).
- `assessClusterStorm(ns, storm)` (`:81-117`): `nodeNotReady` = algum nó com condição `Ready` ≠ `True` (erro → false); `unavailable` = deployments com `desired>0 && available 0` (atenção: `spec` presente com `replicas` nulo → desired nulo → excluído); `total` = deployments com `desired>0`; `percent = unavailable*100/total`; `massOutage = total>0 && unavailable>=min-affected-deployments && percent>=deployment-threshold-percent`; `stormActive = nodeNotReady || massOutage`; `reason` = `NODE_NOT_READY` | `MASS_DEPLOYMENT_UNAVAILABLE` | `NONE`; nomes = label `app` ou nome, distintos e ordenados.
- Mutações: `deletePod`, `rolloutRestart` (`rolling().restart()`), `rollbackRevision` (`rolling().undo()`), `scaleDeployment` (`:389-403`). `KubernetesOpsAdapter.rolloutRestart` idem (`out/k8s/KubernetesOpsAdapter.java:20-23`).
- Cliente Fabric8 `@Lazy`, kubeconfig por arquivo se `app.kubernetes.kubeconfig-path` existir, senão auto/in-cluster; connect 8 s, request 15 s, scale 15 s (`infra/config/KubernetesClientConfig.java:19-63`).

### 4.20 E-mail — `EmailNotificationService` (`out/notification/EmailNotificationService.java:30-324`)

Dois caminhos:
- **`sendHtmlTo`** (mesa, `:52-69`): `cid = correlationId.toString()` ou `UUID.nameUUIDFromBytes(serviceName|logContext|subject|recipient)`; `publishToGoogleSender`; auditoria `GUARDIAN_ALERT_SENT/FAILED` se `publishAudit`. **Sem fallback HTTP.**
- **`send` → `dispatchEmail`** (e-mails "de agente", `:45-50,248-266`): sempre para `app.guardian.default-recipient` (não usa a tabela de destinatários); `cid` = UUID do comando ou `nameUUIDFromBytes(serviceName|logContext|subject)`; auditoria; se a publicação no Rabbit falhou → `fallbackHttp` para ms-communication (`:268-291`).

`publishToGoogleSender` (`:293-312`): `rateLimiter.acquireEmailPermit()`; `to = recipient` (vazio → default); payload `HashMap {tenant_id: app.guardian.tenant-id, x_correlation_id: cid, correlationId: cid, to, subject, html}` → `convertAndSend(app.rabbitmq.email-exchange, app.rabbitmq.email-routing-key, payload)`; exceção → false.

`fallbackHttp`: POST `${app.communication.url:http://ms-communication:8082}/api/v1/messages/send` (`out/feign/CommunicationMessageClient.java:10-21`) headers `X-Company-Id: tenantId`, `X-Correlation-ID: cid`; body `{companyId: tenantId, correlationId, xCorrelationId, messageType: "EMAIL", recipient: defaultRecipient, templateType: "ALERTA_SEGURANCA", subject, communicationType: "EMAIL", codeUser: "ADMIN_GUARDIAN", variables: {serviceName, diagnosticReportHtml: html}}`. Timeouts 2 s/8 s (`out/feign/CommunicationMessageClientConfig.java:16-19`).

`UUID.nameUUIDFromBytes` = MD5 dos bytes UTF-8 **sem namespace**, com versão 3 e variante IETF: em Go `h := md5.Sum(b); h[6] = h[6]&0x0f|0x30; h[8] = h[8]&0x3f|0x80`. Concatenação com `null` vira `"null"`.

E-mails de agente (assunto e variáveis exatos):

| Método | Template | Assunto | Variáveis | Linhas |
|---|---|---|---|---|
| `sendIncidentDiagnosticEmail` (**não usado**) | incident-diagnostic | `"🚨 [KeepGuard AI Guardian] Incidente: <svc> (<pt.severity>)"` | headerColor por severidade (CRITICAL `#dc2626`, HIGH `#ea580c`, MEDIUM `#d97706`, outros `#2563eb`), serviceName, podName, severity, rootCause/recommendedAction (`html()`), generatedAt `dd/MM/yyyy HH:mm:ss` | `:71-96` |
| `sendPrOpenedEmail` | pr-opened | `"🛠️ [AI Guardian] PR #<n> aberto: <repo>"` | headerColor `#0f766e`, repoName, prNumber, branchName, errorReason (pt), rootCause, filePath, recommendedAction, prUrl | `:98-119` |
| `sendPrReadyForHumanApprovalEmail` | pr-ready-for-approval | `"🤖 [AI Guardian Review] PR #<n> Pronto para sua Aprovação (<repo>)"` | `#2563eb`, prNumber, repoName, aiFeedback (`html()`), prUrl | `:121-138` |
| `sendCommentRepliedEmail` | comment-replied | `"💬 [CoderAgent] Resposta ao Comentário no PR #<n> (<repo>)"` | `#0284c7`, repoName, prNumber, author, userComment, badgeHtml (verde "✅ Alteração Aplicada & Commit Gerado" / azul "ℹ️ Esclarecimento Técnico (Sem Commit)", HTML inline exato em `:144-146`), agentResponse, prUrl | `:140-164` |
| `sendDeployStartedEmail` | deploy-started | `"⏳ [AI Guardian Deploy] Iniciando Rollout do <repo> no Kubernetes"` | `#d97706`, prNumber, mergedBy, repoName | `:166-181` |
| `sendDeployCompletedEmail` | deploy-completed | `"🎉 [AI Guardian Deploy] Hotfix do <repo> Publicado no Kubernetes!"` | `#16a34a`, prNumber, mergedBy, repoName | `:183-198` |
| `sendDataInconsistencyEmail` (**não usado**) | data-inconsistency | `"⚠️ [BusinessAnalystAgent] Inconsistência de Dados / Regra de Negócio em <svc>"` | `#d97706`, serviceName, summary, businessContext, sqlBlock | `:200-222` |
| `sendInfrastructureAlertEmail` (**não usado**) | infrastructure-alert | `"⚙️ [KeepGuard AI Guardian] Alerta Operacional / Infraestrutura em <svc>"` | `#475569`, serviceName, summary, context, suggestedAction | `:224-242` |

`prUrl` = `pr.prUrl` ou `"https://github.com/keepguard/<repo>/pull/<n>"` (`:314-319`). `nvl` = `PlaceholderRenderer.nvl` (nulo/vazio → `"—"`). Os `Map.of(...)` lançam NPE se algum valor for nulo (ex.: `serviceName` nulo no fallback HTTP, `:281-283`).

### 4.21 LLM — adapters (`infra/llm/`)

Exatamente um `LlmPort` ativo:
- `GatewayLlmAdapter` quando provider **não** é `ollama` nem `openai` (inclui `none`, `gateway` e `anthropic`) (`infra/llm/GatewayLlmAdapter.java:24`). `available() = provider ∉ {"", "none"} && gateway-url não vazio` (`:48-51`, `infra/config/GuardianLlmProperties.java:49-51`).
- `SpringAiLlmAdapter` quando `ollama|openai` (`infra/llm/SpringAiLlmAdapter.java:22`); `available() = ChatClient.Builder presente && provider habilitado`.

`GatewayLlmAdapter.complete` (`:54-114`): indisponível → registra invocação com fallback e retorna vazio. `prompt = LlmPromptSupport.withPortugueseNarrativeRule(request)` (prefixa a regra só para `sre.investigate`/`reviewer.hotfix-scope` e se o prompt não contém `"português brasileiro"` nem `"Idioma obrigatório"`, `infra/llm/LlmPromptSupport.java:11-25`). POST `${app.guardian.llm.gateway-url}/api/v1/llm/complete` (`out/feign/LlmGatewayClient.java:9-21`), headers `X-Company-Id` (só se tenant não vazio), `X-Correlation-ID` (incidentId ou UUID novo), `Authorization: Bearer <token>` (token OAuth do ms-auth se `tenant-id` é UUID e há secret-base; senão `gateway-bearer-token`; senão ausente, `:134-149`). Corpo `CompleteRequest` com `@JsonInclude(NON_EMPTY)` (omite nulos e strings vazias): `{providerId?, model?, messages:[{role:"user",content}], maxTokens, temperature, feature: promptKey, companyId?, correlationId, sourceService:"ms-ai-guardian"}` (`infra/llm/GatewayLlmDtos.java:12-25`). Resposta `{content, model, providerType, usage{promptTokens, completionTokens, totalTokens, estimatedCostUsd, latencyMs}}` (desconhecidos ignorados, `:27-42`). Timeout lógico = `request.timeoutSeconds` via `CompletableFuture.orTimeout` (não cancela o HTTP); Feign 5 s connect / 95 s read (`out/feign/LlmGatewayClientConfig.java:16-19`). Conteúdo vazio/erro → vazio.

`record(...)` (`:116-132`): grava `LlmInvocation{incidentId, promptKey, promptVersion, model = response.model ?: "gateway" (SpringAi: nome do provider), inputHash = md5hex(prompt com regra), output (até 8000 chars), latencyMs, fallbackUsed}`; falha só loga em debug (ex.: `model` > 64 chars).

`AuthTokenAdapter.getToken(companyId)` (`out/feign/AuthTokenAdapter.java:43-95`): sem `auth.secret-base` → vazio. Cache por company até `expiry - token-renew-before-seconds (600)`. Secret: GET `${auth.base-url}/api/v1/auth/oauth/runtime/secret?clientId=<auth.client-id>` com headers `X-Company-Id`, `X-Auth-Client-Secret-Base: <secret-base>` → `{clientId, secretEncrypted, status}`; decifra (`infra/oauth/OAuthSecretCrypto.java:19-38`: base64 → `iv = 12 bytes | ciphertext+tag`, AES-256-GCM tag 128, chave = SHA-256(secretBase)); cache **eterno** do secret. Token: POST `/api/v1/auth/oauth/token` header `X-Company-Id`, body `{"grantType":"client_credentials","clientId","clientSecret"}` → `{accessToken, expiresIn}` (`expiresIn<=0` → 3600). Timeouts 2 s/5 s (`out/feign/AuthTokenClientConfig.java:16-19`).

### 4.22 GitHub — `GitHubApiClient` (`out/github/GitHubApiClient.java:25-255`)

Toda chamada adquire permit do bucket `GITHUB` e exige `app.github.token` (vazio → `IllegalStateException("GITHUB_TOKEN não configurado no AI Guardian.")`); headers `Authorization: Bearer <token>`, `Accept: application/vnd.github+json`; base `${app.github.api-url:https://api.github.com}`, owner `app.github.owner` (`keepguard`); timeouts 5 s/20 s (`out/feign/GitHubClientConfig.java:16-19`).

| Método porta | Chamada | Erro | Linhas |
|---|---|---|---|
| `getBranchSha` | GET `/repos/{o}/{r}/git/ref/heads/{b}` → `object.sha` | lança `RuntimeException("Falha ao obter branch SHA: ...")` | `:49-59` |
| `createBranch` | POST `/git/refs` `{ref:"refs/heads/<b>", sha}` | false | `:61-76` |
| `listSourceFilePaths` | sha da branch + GET `/git/trees/{sha}?recursive=1`; só `blob` com extensão `.go/.java/.kt/.kts` e sem `/vendor/ /node_modules/ /target/ /.git/ /dist/` | `[]` | `:78-112` |
| `getFileContent` | GET `/contents/{path}?ref=` → `{sha, content = base64 decode (sem espaços)}` | `{}` | `:114-127` |
| `commitFileChange` | PUT `/contents/{path}` `{message, content: base64, branch, sha?}` | false | `:129-147` |
| `createPullRequest` | POST `/pulls` `{title, body, head, base}` → `{prNumber: number, htmlUrl: html_url}` | lança | `:149-167` |
| `submitReview` | POST `/pulls/{n}/reviews` `{body, event}` | false | `:169-185` |
| `addComment` | POST `/issues/{n}/comments` `{body}` | false | `:187-198` |
| `replyToPrReviewComment` | POST `/pulls/{n}/comments` `{body, in_reply_to: long(commentId)}`; erro → `addComment` | — | `:200-216` |
| `getPrReviewComments` | GET `/pulls/{n}/comments` (só 1ª página) → `[{id, body, author: user.login, path}]` | `[]` | `:218-239` |
| `getPullRequestStatus` | GET `/pulls/{n}` → `{merged, state, mergedBy: merged_by.login ?: "human"}` | `{merged:false, state:"open", mergedBy:""}` | `:241-254` |

---

## 5. Persistência

- PostgreSQL, schema `ms_ai_guardian` (`res/application.yml:27` + `schema=` em cada `@Table`), `ddl-auto: update` (`:22`), `open-in-view: false` (`:20`), Hikari pool 5 / min-idle 1 / timeout 10 s (`:15-18`). Sem Flyway/Liquibase.
- IDs: `@GeneratedValue(strategy = UUID)` → UUID gerado na aplicação [framework]. Colunas implícitas seguem `CamelCaseToUnderscoresNamingStrategy` (ex. `podName` → `pod_name`) [framework].
- Tipos [framework, Hibernate 6.6 + PostgreSQL]: `UUID` → `uuid`; `String` sem `length` → `varchar(255)`; `LocalDateTime` → `timestamp(6)` (sem time zone); primitivos (`int`, `boolean`) → `NOT NULL`; `@Enumerated(STRING)` → `varchar(n)` **provavelmente com `CHECK (col in (...))`** gerado na criação da tabela — confirmar no schema de prod.
- **Nenhum índice secundário e nenhuma FK** em nenhuma entidade (consultas por `incident_id`, `fingerprint`, `status` fazem seq scan).
- `@CreationTimestamp`/`@UpdateTimestamp` usam o relógio da JVM.

### 5.1 Tabelas (12)

**`incidents`** (`infra/persistence/entity/IncidentJpaEntity.java:23-107`)

| Coluna | Tipo | Null | Obs |
|---|---|---|---|
| id | uuid PK | não | |
| namespace, pod_name, service_name, error_reason | varchar(255) | **não** | `:31-41` |
| severity, status | varchar enum | não | `:43-49` |
| captured_logs_snippet, ai_root_cause_analysis, ai_recommended_action | text | sim | `:51-58` |
| fingerprint | varchar(64) | sim | `:60-61` |
| occurrences_count | integer | não | `:63-65` |
| last_seen_at, notification_sent_at | timestamp(6) | sim | |
| target_recipient_email | varchar(255) | sim | |
| notification_sent | boolean | não (primitivo) | |
| k8s_conclusion | varchar(64) | sim | `:75-76` |
| investigation_source | varchar(32) enum | sim | `:78-80` |
| correlation_id | varchar(64) | sim | `:82-83` |
| healthy_streak | integer `DEFAULT 0` | não | `:85-88` |
| normalized_at | timestamp(6) | sim | |
| closed_by | varchar(16) enum | sim | `:93-95` |
| reopened_from_id | uuid | sim | |
| ai_summary | text | sim | `:100-101` |
| created_at, updated_at | timestamp(6) | sim | `@CreationTimestamp` (sem `updatable=false`) / `@UpdateTimestamp` |

**`incident_evidence`** (`IncidentEvidenceJpaEntity.java:18-35`): id; incident_id uuid NOT NULL; kind varchar(32) NOT NULL; payload_json text NOT NULL; created_at.

**`incident_action_suggestions`** (`IncidentActionSuggestionJpaEntity.java:20-54`): id; incident_id NOT NULL; action_type varchar(32) enum NOT NULL; label varchar(255) NOT NULL; risk varchar(16) enum NOT NULL; enabled boolean NOT NULL; disabled_reason, ai_rationale, payload_json text; created_at.

**`incident_action_executions`** (`IncidentActionExecutionJpaEntity.java:18-56`): id; incident_id, suggestion_id uuid NOT NULL; actor_user_id, actor_email, actor_role varchar(255); correlation_id varchar(64); outcome varchar(32) NOT NULL; before_json, after_json, error_message text; created_at.

**`incident_alert_deliveries`** (`IncidentAlertDeliveryJpaEntity.java:19-43`): id; incident_id NOT NULL; email varchar(320) NOT NULL; outcome varchar(16) enum NOT NULL; kind varchar(32); correlation_id varchar(64); sent_at (`@CreationTimestamp`).

**`incident_lifecycle_events`** (`IncidentLifecycleEventJpaEntity.java:19-40`): id; incident_id NOT NULL; event_type varchar(32) enum NOT NULL; detail text; correlation_id varchar(64); created_at.

**`guardian_alert_recipients`** (`GuardianAlertRecipientJpaEntity.java:19-40`): id; email varchar(320) NOT NULL, `UNIQUE uk_guardian_alert_recipient_email`; enabled boolean NOT NULL; label varchar(120); created_at; updated_at.

**`pull_request_lifecycles`** (`PullRequestLifecycleJpaEntity.java:13-77`): id; incident_id uuid; repo_name, branch_name, base_branch varchar(255) NOT NULL; pr_number integer; pr_url, file_path varchar(255); ai_reviewed, ai_approved, human_approved, merged_by_human, deployed_to_k8s boolean NOT NULL (primitivos); ai_review_feedback text; last_processed_comment_id varchar(255); status varchar(32) enum NOT NULL; created_at (`updatable=false`); updated_at.

**`processed_comments`** (`ProcessedCommentJpaEntity.java:11-31`): id; comment_id varchar(255) NOT NULL **UNIQUE** (nome gerado pelo Hibernate); pr_number integer NOT NULL; processed_at (`updatable=false`).

**`prompt_templates`** (`PromptTemplateJpaEntity.java:21-56`): id; prompt_key varchar(80) NOT NULL; version varchar(32) NOT NULL; body text NOT NULL; status varchar(16) NOT NULL; checksum varchar(64); created_at (`updatable=false`); updated_at; `UNIQUE uk_prompt_templates_key_version (prompt_key, version)`.

**`llm_invocations`** (`LlmInvocationJpaEntity.java:19-58`): id; incident_id uuid; prompt_key varchar(80); prompt_version varchar(32); model varchar(64); input_hash varchar(64); output text; latency_ms bigint; fallback_used boolean NOT NULL; created_at (`updatable=false`).

**`classification_rules`** (`ClassificationRuleJpaEntity.java:24-75`): id; rule_key varchar(80) NOT NULL `UNIQUE uk_classification_rules_rule_key`; priority integer NOT NULL; verdict varchar(40) enum NOT NULL; requires_code_pr boolean NOT NULL; error_contains, logs_contains, summary_template, explanation_template, suggested_action_template text; enabled boolean NOT NULL; created_at (`updatable=false`); updated_at.

### 5.2 Consultas (Spring Data, todas derivadas; nenhuma `@Query`)

| Repositório | Método → SQL equivalente | Usado? | Linhas |
|---|---|---|---|
| Incident | `findAll(Specification, Pageable)` (busca 1.3) + `count(*)` | sim | `infra/persistence/IncidentRepositoryAdapter.java:43-45,78-110` |
| Incident | `findFirstByFingerprintOrderByCreatedAtDesc` → `WHERE fingerprint=? ORDER BY created_at DESC LIMIT 1` | sim | `infra/persistence/spring/IncidentSpringRepository.java:20` |
| Incident | `findByStatusIn` → `WHERE status IN (...)` sem ordem | sim | `:27` |
| Incident | `findByNamespaceOrderByCreatedAtDesc`, `findTopByPodNameAndCreatedAtAfter...`, `findTopByServiceNameAndErrorReasonAndCreatedAtAfter...` | **não** | `:18,22,24-25` |
| Evidence | `findByIncidentIdOrderByCreatedAtDesc` | sim | `IncidentEvidenceSpringRepository.java:12` |
| Suggestion | `findByIncidentIdOrderByCreatedAtAsc`; `deleteByIncidentId` (`@Modifying @Transactional` — derivado: carrega e remove uma a uma [framework]) | sim | `IncidentActionSuggestionSpringRepository.java:14-18` |
| Execution | `findByIncidentIdOrderByCreatedAtDesc` | sim | `IncidentActionExecutionSpringRepository.java:12` |
| Delivery | `findByIncidentIdOrderBySentAtDesc` | sim | `IncidentAlertDeliverySpringRepository.java:12` |
| Lifecycle | `findByIncidentIdOrderByCreatedAtAsc` | sim | `IncidentLifecycleEventSpringRepository.java:12` |
| Recipient | `findAllByOrderByCreatedAtAsc`, `findByEnabledTrueOrderByCreatedAtAsc`, `findByEmailIgnoreCase` (`upper(email)=upper(?)` [framework]), `countByEnabledTrue`, `count` | sim | `GuardianAlertRecipientSpringRepository.java:13-19` |
| PR | `findByRepoNameAndPrNumber` (Optional — >1 linha → `IncorrectResultSizeDataAccessException` [framework]), `findByRepoNameAndIncidentIdAndStatusIn` (incidentId nulo → `IS NULL` [framework]), `findByStatusIn`; `findByRepoNameAndBranchName` **não usado** | — | `PullRequestLifecycleSpringRepository.java:15-22` |
| ProcessedComment | `existsByCommentId` | sim | `ProcessedCommentSpringRepository.java:11` |
| Prompt | `findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc`, `existsByPromptKeyAndStatus` (não usado) | — | `PromptTemplateSpringRepository.java:13-15` |
| Rule | `findByEnabledTrueOrderByPriorityAsc`, `existsByRuleKey`, `count` | sim | `ClassificationRuleSpringRepository.java:13-15` |

`save` dos adapters converte domínio→entidade e chama `JpaRepository.save` (merge quando há id) (ex. `infra/persistence/IncidentRepositoryAdapter.java:32-35`). Cada `save` é uma transação própria, salvo dentro dos poucos métodos `@Transactional`.

### 5.3 Seeders (boot)

`CatalogSeeder` (`ApplicationRunner`, `infra/config/CatalogSeeder.java:20-40`):
1. Para cada uma das 12 `PromptKeys`: `CompositePromptCatalog.seedOrRefresh(key)` (`infra/prompt/CompositePromptCatalog.java:79-125`): `body = classpath prompts/<key>.st`, `checksum = md5hex(body)`; sem linha ACTIVE → insere `version "1"`, `status "ACTIVE"`; com linha e (checksum ou body diferente) → **atualiza a mesma linha** (`body`, `checksum`, `version = nextVersion`: nulo/vazio/`classpath` → `"2"`, inteiro → +1, outro → `<v>.pt`), apaga o cache Redis; igual → nada. Erros por chave só logam.
2. `ClassificationCatalog.seedIfEmpty()` (`infra/classification/ClassificationCatalog.java:60-82`): só se `count()==0`; insere cada regra do YAML (`errorContains/logsContains` unidos por `"\n"`, já em minúsculas).

---

## 6. Redis / cache

Prefixo `app.guardian.redis.key-prefix` = `guardian` (`res/application.yml:105`). Todos via `StringRedisTemplate` (valores string).

| Uso | Chave | Valor | TTL | Fallback sem Redis | Linhas |
|---|---|---|---|---|---|
| Cache de prompt | `guardian:prompt:<key>` | `"<version>\n<body>"` (ignorado se `\n` na posição 0 ou ausente) | `prompt-cache-ttl-seconds` 300 s | lê DB/classpath | `infra/prompt/CompositePromptCatalog.java:39-66` |
| Invalidação de prompt | `DEL guardian:prompt:<key>` | — | — | — | `:108-114` |
| Rate limit | `guardian:rl:<github|llm|email>:<epochSecond>` | `INCR`; `EXPIRE 2s` quando =1 | 2 s | Bucket4j local bloqueante, capacidade `max(2×rps,4)`, refill guloso `rps/s` | `infra/ratelimit/RateLimiterService.java:33-78` |
| Lock | `guardian:lock:<deploy:svc>` | ownerId; `SET NX EX ttl` | `lock-ttl-seconds` 600 | `ConcurrentHashMap` sem TTL | `infra/redis/RedisDistributedLockAdapter.java:33-65` |
| Release do lock | Lua `if get==ARGV[1] then del` | — | — | remove do mapa local | `:20-27,51-61` |
| Idempotência webhook | `guardian:idem:gh:<deliveryId>` | `"1"` `SET NX EX` | `idempotency-ttl-seconds` 86400 | mapa local eterno | `infra/redis/RedisIdempotencyAdapter.java:22-33` |
| Cooldown de alerta | `guardian:alert-cd:<scope>` (escopos em 4.10) | `"1"` `SET NX EX` | `anti-flapping-cooldown-minutes`×60 (900 s) | mapa local (janela) | `infra/redis/RedisAlertCooldownAdapter.java:22-43` |
| Estado de tempestade | `guardian:storm:<namespace>` | JSON `{"namespace","incidentId","startedAtEpochMs","confirmStreak","nodeNotReady","affectedServices"}` | `max(state-ttl-seconds, 60)` = 7200 s | mapa local (gravado sempre; lido só se Redis falhar) | `infra/redis/RedisClusterStormStateAdapter.java:26-65`, `app/dto/ClusterStormState.java:7-62` |

Rate limit acima do limite: dorme 200 ms e chama `acquire` recursivamente (cada retentativa incrementa o contador, recursão sem limite) (`RateLimiterService.java:43-46,72-78`). Limites: GitHub 10/s, LLM 5/s, e-mail 20/s (`res/application.yml:110-113`). Cooldown com `scope` vazio ou `cooldownMinutes<=0` → sempre adquire (`RedisAlertCooldownAdapter.java:24-26`). `llm-cache-ttl-seconds` não tem implementação.

---

## 7. Configuração

### 7.1 `application.yml` (`res/application.yml`)

| Propriedade | Env var / default | Linha |
|---|---|---|
| `server.port` | 8088 | `:2` |
| `spring.threads.virtual.enabled` | true | `:5-7` |
| `spring.datasource.url/username/password` | `jdbc:postgresql://localhost:5432/keepguard_api_db` / `keepguard_api_user` / `keepguard_api_pass` | `:11-13` |
| `spring.datasource.hikari.*` | timeout 10000, max 5, min-idle 1 | `:15-18` |
| `spring.jpa.*` | open-in-view false, ddl-auto update, default_schema `ms_ai_guardian` | `:19-27` |
| `spring.data.redis.host/port` | `SPRING_DATA_REDIS_HOST:localhost` / `SPRING_DATA_REDIS_PORT:6379`; timeout 3000 ms; pool 8/4/1, max-wait 2000 ms | `:28-38` |
| `spring.rabbitmq.*` | localhost:5672 guest/guest, connection-timeout 8000 | `:39-44` |
| `spring.ai.ollama.*` | base-url `SPRING_AI_OLLAMA_BASE_URL:http://ollama:11434`; pull-model-strategy never; chat.enabled `APP_GUARDIAN_OLLAMA_ENABLED:false`; model `SPRING_AI_OLLAMA_MODEL:qwen2.5-coder:1.5b`; temperature `APP_GUARDIAN_LLM_TEMPERATURE:0.2`; num-predict `APP_GUARDIAN_LLM_MAX_TOKENS:256` | `:48-60` |
| `spring.ai.openai.*` | api-key `SPRING_AI_OPENAI_API_KEY:disabled`; base-url `SPRING_AI_OPENAI_BASE_URL:https://api.openai.com`; embedding off; chat.enabled `APP_GUARDIAN_OPENAI_ENABLED:false`; model `SPRING_AI_OPENAI_MODEL:gpt-4.1-mini`; temperature/max-tokens idem | `:61-71` |
| `spring.ai.anthropic.*` | `SPRING_AI_ANTHROPIC_API_KEY:`, `APP_GUARDIAN_ANTHROPIC_ENABLED:false`, `SPRING_AI_ANTHROPIC_MODEL:claude-3-5-haiku-latest` (starter só no profile Maven `anthropic`, `pom.xml:225-233` → inerte) | `:72-79` |
| `app.kubernetes.kubeconfig-path` | `""` | `:82-83` |
| `app.github.token` / `owner` | `GITHUB_TOKEN:` / `keepguard` (adapter também aceita `${GITHUB_TOKEN}` direto, `out/github/GitHubApiClient.java:33-37`) | `:84-86` |
| `app.github.api-url` | (não no YAML) default `https://api.github.com` | `out/feign/GitHubClient.java:16` |
| `app.communication.url` | (não no YAML) default `http://ms-communication:8082` | `out/feign/CommunicationMessageClient.java:12` |
| `app.guardian.namespace` | `keepguard` | `:88` |
| `app.guardian.watcher-enabled` | true | `:89` |
| `app.guardian.scan-interval-ms` | 60000 (lido pelo `@Scheduled`) | `:90` |
| `app.guardian.anti-flapping-cooldown-minutes` | 15 | `:91` |
| `app.guardian.healthy-streak-required` | 3 (lido via `@Value` na reconciliação) | `:92` |
| `app.guardian.max-alert-recipients` | 20 | `:93` |
| `app.guardian.storm.*` | deployment-threshold-percent 40, min-affected-deployments 5, infra-alert-confirm-scans 2, state-ttl-seconds 7200 | `:94-98` |
| `app.guardian.console-url` | `APP_GUARDIAN_CONSOLE_URL:https://app-core.keepguard.com.br` | `:99` |
| `app.guardian.default-recipient` | `APP_GUARDIAN_DEFAULT_RECIPIENT:` | `:100` |
| `app.guardian.tenant-id` | `APP_GUARDIAN_TENANT_ID:` | `:101` |
| `app.guardian.approver-github` | `APP_GUARDIAN_APPROVER_GITHUB:human` | `:102` |
| `app.guardian.approver-display-name` | `APP_GUARDIAN_APPROVER_DISPLAY_NAME:time` | `:103` |
| `app.guardian.redis.*` | key-prefix guardian, lock-ttl 600, idempotency-ttl 86400, prompt-cache-ttl 300, llm-cache-ttl 3600 (não usado) | `:104-109` |
| `app.guardian.rate-limit.*` | github 10, llm 5, email 20 | `:110-113` |
| `app.guardian.llm.provider` | `APP_GUARDIAN_LLM_PROVIDER:none` | `:118` |
| `app.guardian.llm.gateway-url` | `LLM_GATEWAY_URL:http://srv-llm-gateway:8650` | `:119` |
| `app.guardian.llm.gateway-bearer-token` | `LLM_GATEWAY_BEARER_TOKEN:` | `:120` |
| `app.guardian.llm.provider-id` / `model` | `APP_GUARDIAN_LLM_PROVIDER_ID:` / `APP_GUARDIAN_LLM_MODEL:` | `:121-122` |
| `app.guardian.llm.timeout-seconds` / `codegen-timeout-seconds` | `APP_GUARDIAN_LLM_TIMEOUT_SECONDS:45` / `APP_GUARDIAN_LLM_CODEGEN_TIMEOUT_SECONDS:90` | `:123-124` |
| `app.guardian.llm.max-tokens` / `temperature` | `APP_GUARDIAN_LLM_MAX_TOKENS:256` / `APP_GUARDIAN_LLM_TEMPERATURE:0.2` | `:125-126` |
| `app.rabbitmq.exchange` / `routing-key` | `ms-communication-exchange-dev` / `communication.message.send` (exchange declarado mas nunca usado para publicar, `infra/config/RabbitMQConfig.java:14-20`) | `:128-129` |
| `app.rabbitmq.email-exchange` / `email-routing-key` | `srv-email-google-sender-exchange-dev` / `email.google.send` | `:130-131` |
| `auth.base-url` | `AUTH_BASE_URL:http://ms-auth:8081` | `:134` |
| `auth.client-id` | `AUTH_CLIENT_ID:ms-ai-guardian` | `:135` |
| `auth.secret-base` | `AUTH_CLIENT_SECRET_BASE:` | `:136` |
| `auth.token-renew-before-seconds` | `AUTH_TOKEN_RENEW_BEFORE_SECONDS:600` | `:137` |
| `keepguard.audit.*` | enabled true; exchange `KEEPGUARD_AUDIT_EXCHANGE:srv-audit-exchange-dev`; routing-key `audit.event`; source-service `ms-ai-guardian` (não lido; hardcoded no publisher) | `:139-144` |
| `management.*` | probes on, liveness/readiness on, redis health off, circuitbreakers health on, exposição `health,info,prometheus` | `:146-163` |
| `resilience4j.*` | CB default (window 10, 50%, open 30 s, half-open 3) e retry (2 tentativas, 500 ms) para `llm-gateway` e `github-api` — **sem uso no código** | `:165-187` |

Qualquer propriedade pode ser sobrescrita por env via relaxed binding (o Helm usa `APP_GUARDIAN_NAMESPACE`, `APP_GUARDIAN_WATCHER_ENABLED`, `SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_HOST/PORT`, `helm/templates/deployment.yaml:28-97`). `GITHUB_TOKEN` vem do secret `ms-ai-guardian-secret` (`:93-97`).

### 7.2 Profiles
- `application-local.yml` (`res/application-local.yml:1-31`): datasource `postgres:5432` user/pass fixos; rabbit `rabbitmq-service` guest/guest; redis `redis`; gateway-url `LLM_GATEWAY_URL:http://localhost:8650`; audit exchange `srv-audit-exchange-local`.
- `application-prod.yml` (`res/application-prod.yml:1-24`): datasource `postgres:5432`; rabbit `rabbitmq-service:5672` (credenciais herdadas `guest/guest`); redis `redis`; audit exchange `srv-audit-exchange-prod`. **Não sobrescreve `app.rabbitmq.email-exchange`** (segue `-dev`).

### 7.3 Classes de propriedades
- `GuardianProperties` (`@ConfigurationProperties("app.guardian")`, `infra/config/GuardianProperties.java:9-56`): defaults em código iguais ao YAML (`namespace keepguard`, `watcherEnabled true`, `scanIntervalMs 60000` (não usado), `antiFlappingCooldownMinutes 15`, `healthyStreakRequired 3` (não usado — a reconciliação lê via `@Value`), `maxAlertRecipients 20`, `consoleUrl`, `defaultRecipient ""`, `tenantId ""`, `approverGithub "human"`, `approverDisplayName "time"`, `Redis{keyPrefix guardian, lockTtl 600, idempotencyTtl 86400, promptCacheTtl 300, llmCacheTtl 3600}`, `RateLimit{10,5,20}`, `Storm{40,5,2,7200}`).
- `GuardianLlmProperties` (`@Value`, `infra/config/GuardianLlmProperties.java:16-57`): `provider none`, `gatewayUrl`, `gatewayBearerToken`, `providerId`, `model`, `timeoutSeconds 45`, `codegenTimeoutSeconds 90`, `maxTokens 256`, `temperature 0.2`; `isEnabled()`; loga config no `@PostConstruct`.

### 7.4 RabbitMQ (topologia declarada pelo serviço)
`guardian.incident.exchange` (topic, durável) → fila `guardian.incident.process.queue` (durável, args `x-dead-letter-exchange=guardian.incident.exchange`, `x-dead-letter-routing-key=guardian.incident.process.dlq.rk`) via RK `guardian.incident.process`; fila `guardian.incident.process.dlq` (durável) via RK `guardian.incident.process.dlq.rk` (`infra/messaging/RabbitMqTopologyConfig.java:12-47`). Também declara `ms-communication-exchange-dev` (topic) (`infra/config/RabbitMQConfig.java:17-20`) e, sob demanda, a exchange de auditoria (4.18). O Go precisa declarar argumentos **idênticos** (senão `PRECONDITION_FAILED`). Mensagens publicadas carregam header `__TypeId__` (`java.util.HashMap` para e-mail/auditoria) [framework].

---

## 8. Templates, prompts e utilitários

**Recomendação**: embutir os arquivos de `res/prompts/*.st`, `res/templates/email/*` e `res/classification-rules.yml` byte a byte (`go:embed`); o checksum MD5 do prompt é comparado com o banco no boot (5.3), então qualquer diferença de bytes gera nova versão.

### 8.1 Prompts (`res/prompts/`) — placeholders `{{x}}`

| Arquivo | Placeholders | Linhas |
|---|---|---|
| `sre.investigate.st` | errorReason, errorReasonLabel, namespace, serviceName, podName, deploymentName, desiredReplicas, availableReplicas, readyReplicas, replicaSetCount, phase, phaseLabel, waitingReason, waitingReasonLabel, terminatedReason, terminatedReasonLabel, restartCount, crashLoop, imagePullFailure, replicasIntentionallyZero, conclusion, conclusionLabel, warningEvents, logsSnippet. Pede JSON no formato `{"rootCause":"...","summary":"...","recommendedActionIds":["DISMISS"],"riskNotes":"..."}` (`:19`) | `:1-19` |
| `coder.hotfix.st` | language, serviceName, filePath, errorReason, rootCause, incidentLine, scopeHint, functionSource, stackTrace | `:1-19` |
| `coder.review-adjust.st` | feedback, currentCode | `:1-8` |
| `reviewer.hotfix-scope.st` | serviceName, filePath, errorReason, rootCause, code; exige 1ª linha `VEREDITO: APROVADO|REPROVADO` | `:1-21` |
| `github.pr-body.st` | serviceName, podName, severity, errorReason, filePath, rootCause, action, businessSection, archSection, qaSection, approverGithub | `:1-27` |
| `github.coder-no-change.st` | commentFeedback (texto começa `🤖 **[CoderAgent] Feedback analisado!**`) | `:1-7` |
| `github.coder-change-applied.st` | commentFeedback, branch, approverGithub | `:1-8` |
| `github.reviewer-approved.st` | feedback, approverGithub (`🤖 **[ReviewerAgent] PARECER TÉCNICO: APROVADO NO ESCOPO DO INCIDENTE**`) | `:1-6` |
| `github.reviewer-rejected.st` | feedback (`⚠️ **[ReviewerAgent] PARECER TÉCNICO: HOTFIX INSUFICIENTE PARA O INCIDENTE**`) | `:1-3` |
| `architecture.summary.st` | file, error | `:1-3` |
| `architecture.current-flow.st` / `proposed-flow.st` | serviceName, file, error (blocos ```` ```mermaid sequenceDiagram ```` ) | `:1-13` |

Os marcadores `[CoderAgent]`/`[ReviewerAgent]` nos textos são o que impede o loop de comentários (4.13).

### 8.2 Templates de e-mail (`res/templates/email/`)
`_styles.css` (placeholder `headerColor`, `:3,9,11`); HTMLs com `{{styles}}` no `<style>` exceto `mesa.html` (estilo inline, sem `{{styles}}`). Placeholders: `comment-replied` (styles, repoName, prNumber, approverDisplayName, author, userComment, badgeHtml, agentResponse, prUrl); `data-inconsistency` (serviceName, approverDisplayName, summary, businessContext, sqlBlock); `deploy-completed` (approverDisplayName, prNumber, mergedBy, repoName); `deploy-started` (+ namespace); `incident-diagnostic` (serviceName, podName, headerColor, severity, rootCause, recommendedAction, generatedAt); `infrastructure-alert` (serviceName, approverDisplayName, summary, context, suggestedAction); `mesa` (title, serviceName, podName, k8sConclusion, body, extra, ctaUrl, ctaLabel); `pr-opened` (repoName, prNumber, branchName, errorReason, rootCause, filePath, recommendedAction, prUrl); `pr-ready-for-approval` (prNumber, repoName, approverDisplayName, aiFeedback, prUrl).

### 8.3 `PlaceholderRenderer` (`infra/template/PlaceholderRenderer.java:9-36`)
- `render(t, vars)`: `t` nulo → `""`; vars nulo/vazio → `t`; para cada entrada (ordem de iteração do `Map` — `HashMap` não ordenado), `t = t.replace("{{"+k+"}}", v ?: "")`. Placeholder sem variável fica literal. Um valor que contenha `{{outra}}` pode ser substituído depois, dependendo da ordem.
- `nvl(v)`: nulo/branco → `"—"`.
- `html(v)`: nulo/branco → `"—"`; senão `&`→`&amp;`, `<`→`&lt;`, `>`→`&gt;`, `\n`→`<br/>` (nessa ordem; não escapa aspas).

### 8.4 `EmailTemplateRenderer.render(kind, vars)` (`infra/template/EmailTemplateRenderer.java:18-31`)
Copia vars; `putIfAbsent` de `approverDisplayName`, `approverGithub`, `namespace` (das props) e `headerColor` (`#0f766e`); `styles = render(_styles.css, vars)`; `render(<templateName>.html, vars)`. Arquivos lidos do classpath e cacheados para sempre (`infra/template/ClasspathResourceLoader.java:16-32`; ausente → `IllegalStateException("Recurso não encontrado no classpath: ...")`).

### 8.5 `GuardianPortuguese` (`infra/i18n/GuardianPortuguese.java:13-145`)
- `NARRATIVE_LANGUAGE_RULE` (`:15-19`), valor exato: `"Idioma obrigatório: os campos narrativos da resposta (rootCause, summary, riskNotes e qualquer parecer) DEVEM estar em português brasileiro. Não escreva esses campos em inglês. recommendedActionIds e nomes de recursos Kubernetes permanecem com os códigos técnicos.\n"`.
- `norm(v) = trim().toUpperCase(ROOT).replace('-', '_')`; `humanize(v)`: `trim`; se tem espaço devolve como está; senão troca `_` e `-` por espaço e `lowercase(ROOT)`.
- `errorReason(code)`: branco → `"anomalia"`; mapa `ERROR_REASONS` (`:21-35`: SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE, RESTART_OR_FAILURE_DETECTED, PANIC_RUNTIME_EXCEPTION, NULLPOINTEREXCEPTION, CLUSTER_WIDE_OUTAGE, MANUAL_TRIGGER, MANUAL_ASYNC_TRIGGER, CRASHLOOPBACKOFF, PENDING, FAILED, UNKNOWN, RUNNING, SUCCEEDED → textos exatos no arquivo); senão se `upper(code)` começa com `CODE_DEFECT_` → `"Defeito de código: " + code.substring(12).replace('_',' ')` (sobre o código original, sem trim); senão `humanize`.
- `k8sConclusion(enum)`: nulo → `"Conclusão desconhecida"`; `k8sConclusion(String)`: branco → `"sem conclusão K8s"`; mapa `K8S_CONCLUSIONS` (`:37-46`) ou `humanize`.
- `stormReason`: branco → `"motivo não informado"`; mapa (`NODE_NOT_READY` "Nó não pronto", `MASS_DEPLOYMENT_UNAVAILABLE` "Vários deployments indisponíveis", `NONE` "Sem tempestade") ou `humanize`.
- `waitingReason`: branco → `"nenhum"`; mapa `WAITING_REASONS` (`:54-67`) ou `humanize`.
- `phase(code) = errorReason(code)`.
- `severity`: nulo → `"não informada"`; CRITICAL `Crítica`, HIGH `Alta`, MEDIUM `Média`, LOW `Baixa`, INFO `Informativa`.

### 8.6 `PiiLogSanitizer.sanitize(raw)` (`infra/util/PiiLogSanitizer.java:12-78`)
Nulo/branco → devolve o próprio valor. Substituições em ordem:
1. JWT `\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b` → `[REDACTED_SECRET]`.
2. `(?i)Bearer\s+[A-Za-z0-9-_.]+` → `Bearer [REDACTED_SECRET]`.
3. `(?i)(token|password|secret|senha|access_token|refresh_token)\s*[:=]\s*['"]?([^,\s'"\n]+)['"]?` → `$1=[REDACTED_SECRET]`.
4. CPF `\b\d{3}\.\d{3}\.\d{3}-\d{2}\b` → `[REDACTED_CPF]`; `(?i)(?:cpf|documento)\s*[:=]\s*['"]?(\d{11})['"]?` → `cpf=[REDACTED_CPF]`.
5. E-mail `(?i)[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` → `[REDACTED_EMAIL]`.
6. Telefone `(?:\+?55\s?)?(?:\(?\d{2}\)?\s?)?\d{4,5}[-\s]?\d{4}` → `[REDACTED_PHONE]` (casa qualquer sequência de 8–9+ dígitos, inclusive timestamps/IDs numéricos).
Notas Go: em RE2 coloque `-` no fim da classe (`[A-Za-z0-9_.-]`); `$1` em Go é `${1}`; `\b` do Java é Unicode-aware vs ASCII no RE2 (diferença só com letras acentuadas adjacentes).

### 8.7 `LlmContextLimiter` (`infra/util/LlmContextLimiter.java:12-49`)
- `tail(text, max)`: nulo/branco → `""`; `len <= max` → texto; senão `"...[truncado]\n" + últimos max chars` (contagem em UTF-16; em Go contar runes).
- `callWithTimeout(supplier, s, fallback)`: resultado nulo/branco ou timeout/exceção → fallback. `invokeWithTimeout`: nulo/timeout/exceção → fallback. Não cancelam a chamada subjacente.
- Constantes `LOG_CHARS=2500`, `DEFAULT_TIMEOUT_SECONDS=20` não são usadas.

### 8.8 `IncidentSourceLocator` (`infra/util/IncidentSourceLocator.java:17-227`)
- Regex: `FILE_LINE = ([A-Za-z0-9_./\\-]+\.(?:go|java|kt|kts|ts|js|py)):(\d+)`; `JAVA_STACK = \(([A-Za-z0-9_]+\.java):(\d+)\)`; `ZAP_CALLER = "caller"\s*:\s*"([^"]+\.(?:go|java|kt)):(\d+)"` (`:19-24`).
- `parse(logs, reason)` (`:46-64`): `blob = logs + "\n" + reason`; coleta frames de ZAP (zapCaller=true), depois JAVA_STACK, depois FILE_LINE (posição = início do match; `\` → `/`; descarta `isFrameworkFrame`). Primário = maior `(zapCaller?1:0, posição)` → o zap caller mais ao final do texto; sem zap, o frame de maior posição. `relativePaths`/`basenames` = primário primeiro, depois todos os frames na ordem de coleta, sem duplicatas (LinkedHashSet). `lineNumber` = linha do primário.
- `Hint.fingerprintLocation()`: `"<base0>:<line>"`, ou `"<base0>"`, ou `"general"`; `fingerprintLocation(logs, reason)` devolve vazio para `"general"` (`:28-37,66-70`).
- `isFrameworkFrame(path)` (`:132-164`): branco → true; lower com `/`: contém `http:`/`https:`, `/echo/v4`, `labstack/echo`, `/gin-gonic/`, `/go-chi/`, `/gofiber/`, `gorilla/mux`, `/runtime/`, `/net/http/`, `/internal/poll/`, `/database/sql/`, `go/pkg/mod/`, `middleware/recover.go`, `middleware/cors.go`; ou basename ∈ `echo.go, recover.go, cors.go, panic.go, netpoll.go, fd_unix.go, fd_poll_runtime.go, httpservlet.java, dispatcherservlet.java, thread.java, filterchain.java`.
- `rankPaths(tree, hint, reason)` (`:72-85`): ordena **estável** por `pathScore` desc, filtra `> 0`, limita 8.
- `pathScore` (`:87-119`): teste (`/test/`, `_test.go`, `test.java`, `_test.java`) −8; basename = primário +200, senão +25 por cada outro basename igual (índices ≥1); +40 por relativePath que seja sufixo do path e cujo basename = primário; tokens de `lower(reason)` separados por `[^a-z0-9_]+`, com len ≥ 4 e não fracos, contidos no path → +4 cada. Tokens fracos (`:166-170`): `mock, gateway, service, health, http, json, error, incidente, microsservico, keepguard, company, status, database, telecom, internal, handler`.
- `lineExistsIn(src, n)`: verdadeiro sse `n >= 1` e `n <= count('\n') + 1` (src nulo → falso) (`:172-186`).

### 8.9 `ScopedSourcePatcher` (`infra/util/ScopedSourcePatcher.java:13-252`)
- Regex: `GO_FUNC = (?m)^func\s`; `JAVA_METHOD = (?m)^[ \t]*(public|protected|private|static|final|synchronized|native|abstract|[\w.<>,\[\]]+)+\s+\w+\s*\(` (`:15-17`).
- `languageOf(path)`: `.go` → `go`, `.java` → `java`, senão `unknown` (`:235-247`).
- `extract(src, path, reason, rootCause, line)` (`:31-52`): src branco → `Slice("", "", "unknown")`. Divide: go → `splitGoFunctions` (de cada `^func ` até o próximo; **o preâmbulo antes da 1ª func é descartado**, apesar do comentário em `:98`); java → `splitJavaMethods` (cada match; pula match cujos 80 chars seguintes contêm `" class "`, `" interface "`, `" enum "`; < 2 starts → arquivo inteiro); outro → `[src]`. Uma parte igual ao src → slice do arquivo inteiro. `line > 0` → `functionContainingLine`; senão `pickFunction` com `haystack = lower(reason + " " + rootCause)`.
- `functionContainingLine` (`:160-175`): `offset = offsetOfLine(src, n)` (n ≤ 1 → 0; senão índice após o (n−1)-ésimo `\n`, limitado a `len−1`; não existe → −1); escolhe a parte cujo `src.indexOf(parte)` contém o offset (última por maior índice).
- `pickFunction`/`score` (`:129-158`): +50 se `reason` (len ≥ 4) contido em lower(parte); +3 por token de `haystack` (len ≥ 4, não fraco) contido; empate → a primeira parte.
- `applyReplacement(slice, out)` (`:54-76`): slice nulo/arquivo vazio → `stripMarkdown(out)` ou `""`; `cleaned = stripMarkdown(out)`; vazio → arquivo original; slice inteiro → `cleaned`; `rep = isolateFunction(cleaned, fn, lang)`; vazio → original; `idx = full.indexOf(fn)` (<0 → original); resultado = `full[:idx] + ensureTrailingNewline(rep) + full[idx+len(fn):]`.
- `stripMarkdown` (`:78-86`): `trim`, remove `^```[a-zA-Z]*\s*` do início e `` ```\s*$ `` do fim, `trim`.
- `isolateFunction` (`:193-211`): se `out` contém `firstLine(fn).trim()` e não `looksLikeFullFile` → `out`; senão divide `out` (go/java), `sig = signatureHint(fn)`; primeira parte contendo `sig` → ela; senão, se não parece arquivo inteiro → `out`; senão primeira parte contendo `sig` (ou a 1ª linha) → ou `null`.
- `looksLikeFullFile` (`:213-219`): 8 primeiras linhas (cada uma prefixada por `\n`), lower; go: contém `package ` e `func `; outros: `package ` e (`class ` ou `interface `).
- `signatureHint` (`:221-228`): 1ª linha trim; go regex `^func\s+(?:\([^)]*\)\s+)?(\w+)\s*\(` → nome; senão, se len > 12, os primeiros `min(40, len)` chars; senão a linha.
- Notas Go: `\R` não existe em RE2 (usar `\r\n|[\n\v\f\r\x{85}\x{2028}\x{2029}]`); `.` do Java não casa `\r`, `\u0085`, ` `, ` `; `substring`/`length` são em UTF-16 — usar runes.

---

## 9. Testes Java existentes (spec de regressão)

| Teste | Afirma (valores exatos) |
|---|---|
| `test/MsAiGuardianApplicationTests.java:7-10` | vazio (não sobe contexto). |
| `test/application/service/agents/CoderAgentServiceTest.java:11-28` | `applyHeuristicReviewDirectives("public int div(int a, int b) {\n    // guarda\n    return a / b;\n}\n", "pode remover o comentário")` não contém `guarda` e contém `return a / b`; `("int x = 1;\n", "por que esse nome?")` devolve igual. |
| `test/application/service/pr/HandlePrEventUseCaseTest.java:10-15` | `isBotComment("keepguard-bot","qualquer")=true`; `("rafael","🤖 **[CoderAgent] Feedback analisado!**")=true`; `("rafael","pode remover o comentário")=false`. |
| `test/application/service/sre/ActionCatalogPolicyTest.java:16-64` | (a) `CONTROLLER_ALREADY_RETRYING`, crashLoop, desired 1, available 0, RS 2 → RECREATE_POD, ROLLOUT_RESTART, SCALE_REPLAY desabilitados; DISMISS habilitado. (b) `TRANSIENT_INFRA_RECOVERABLE`, desired 2, available 0, RS 2, pod `ms-auth-abc`, dep `ms-auth`, recomendado `ROLLOUT_RESTART` → ROLLOUT_RESTART, RECREATE_POD, SCALE_REPLAY, ROLLBACK_REVISION habilitados. (c) `REPLICAS_INTENTIONALLY_ZERO`, zero=true, 0/0 → SCALE_REPLAY e ROLLOUT_RESTART desabilitados; DISMISS habilitado. |
| `test/application/service/sre/AlertRecipientUseCaseServiceTest.java:41-69` | upsert com MDC `codeUser=user-1`, `correlationId=corr-1` → audit `("GUARDIAN_ALERT_RECIPIENT_UPSERTED","SUCCESS","corr-1","ALERT_RECIPIENT",id,"USER","user-1")`; patch sem MDC → `("GUARDIAN_ALERT_RECIPIENT_PATCHED","SUCCESS",null,"ALERT_RECIPIENT",id,"SYSTEM",null)`. |
| `test/application/service/sre/ClusterStormLogicTest.java:15-69` | Testa a fórmula, não o serviço: 26/30 com min 5 e 40% → massOutage; 3/30 → não; `unavailablePercent(25 total, 10)` ≥ 40; amostra de 12 serviços contém `svc-8` e `+4`. |
| `test/application/service/sre/LlmInvestigationServiceTest.java:13-35` | `heuristic(facts{desired 1, available 0, waiting "NodeNotReady", NODE_FAILURE, healthy false}, "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE")`: rootCause contém `nenhuma réplica disponível` e `Falha de nó`, não contém o código; summary contém `Réplicas desejadas`, `motivo de espera`, `Nó não pronto`, não contém `desired=`; riskNotes contém `mesa SRE`. |
| `test/domain/classification/ClassificationEngineTest.java:19-49` | Com as regras do YAML: (`"auth failed"`, `"empresa/tenant não encontrado para a api key"`) → DATA_INCONSISTENCY, sem PR; (`"ImagePullBackOff"`, `"Failed to pull and unpack image"`) → INFRASTRUCTURE_FAULT, sem PR; (`"PANIC_RUNTIME_EXCEPTION"`, `"panic recover in handler"`) → CODE_DEFECT com PR; (`"something-new"`, `"log sem sinal conhecido"`) → CODE_DEFECT com PR. |
| `test/domain/enums/PullRequestStatusTest.java:10-19` | `isActive` verdadeiro só para OPEN, CHANGES_REQUESTED, AI_APPROVED; `active()` contém OPEN. |
| `test/infrastructure/i18n/GuardianPortugueseTest.java:13-54` | `errorReason("SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE")="Indisponibilidade do serviço: nenhuma réplica disponível"`; `("NullPointerException")="Exceção de ponteiro nulo (NullPointerException)"`; `("CODE_DEFECT_DIV_BY_ZERO")="Defeito de código: DIV BY ZERO"`; `k8sConclusion(NODE_FAILURE)="Falha de nó"`; `("TRANSIENT_INFRA_RECOVERABLE")="Infraestrutura transitória, recuperável"`; `((String)null)="sem conclusão K8s"`; `stormReason("NODE_NOT_READY")="Nó não pronto"`; `waitingReason("CrashLoopBackOff")="Container reiniciando em loop"`; `waitingReason("")="nenhum"`; `severity(MEDIUM)="Média"`, `(CRITICAL)="Crítica"`, `(null)="não informada"`; `errorReason("hotfix deste PR")="hotfix deste PR"`; regra contém `português brasileiro` e não contém `English`. |
| `test/infrastructure/llm/GatewayLlmAdapterTest.java:58-130` | disponível com enabled+url; desabilitado → vazio, sem HTTP, invocação com `fallbackUsed=true`; sucesso (tenant `company-1`, header `X-Company-Id=company-1`, Authorization nulo) → devolve `"causa raiz em português"`, invocação `fallbackUsed=false`, `model="gpt-4.1-mini"`; exceção do client → vazio e fallback=true; tenant UUID com token OAuth `oauth-token` → `Authorization: "Bearer oauth-token"`. |
| `test/infrastructure/redis/RedisAlertCooldownAdapterTest.java:20-36` | Sem Redis (template nulo → fallback local): 1º `tryAcquire` true; 2º no mesmo escopo em 15 min false; escopo `""`/`"   "` sempre true. |
| `test/infrastructure/template/PlaceholderRendererTest.java:14-48` | `render("Olá {{name}}",{name:Rafael})="Olá Rafael"`; `html("a<b\nc")="a&lt;b<br/>c"`; os 12 prompts existem e não são vazios; `sre.investigate.st` contém `português brasileiro`, `{{errorReasonLabel}}`, `{{conclusionLabel}}`; `pr-opened.html` contém `{{prNumber}}`, `mesa.html` contém `{{ctaUrl}}`. |
| `test/infrastructure/util/IncidentSourceLocatorTest.java:13-70` | zap `{"caller":"service/sms_service.go:122",...}` → basenames contém `sms_service.go`, linha 122, location `sms_service.go:122`; `at ...LoginService.authenticate(LoginService.java:88)` → `LoginService.java`, 88; rank com hint de `sms_service.go:122` prefere `internal/core/service/sms_service.go` a `cmd/main.go` e ao `_test.go`; log com stack Echo + `handler.go:38` + zap caller → primário `sms_service.go`, 122, rank 1º `internal/core/service/sms_service.go`; `isFrameworkFrame("echo.go")` e `".../echo/v4@v4.13.3/middleware/recover.go"` true; `internal/core/service/sms_service.go` e `internal/adapters/in/http/handler.go` false. |
| `test/infrastructure/util/PiiLogSanitizerTest.java:11-50` | Log com `teste@empresa.com`, `123.456.789-00`, `+55 11 99999-1234`, `token=abc123xyzSecret`, JWT → nenhum valor original permanece; contém `[REDACTED_EMAIL]`, `[REDACTED_CPF]`, `[REDACTED_PHONE]`, `[REDACTED_SECRET]`; `sanitize(null)=null`, `("")=""`, `("   ")="   "`. |
| `test/infrastructure/util/ScopedSourcePatcherTest.java:10-115` | Arquivo Go com `ProcessBatchSMS` e `ExecuteBugScenario`: reason `CODE_DEFECT_01` + rootCause com "Divisao por zero... tarifacao" → slice `ExecuteBugScenario`; substituição da função mantém `ProcessBatchSMS`, `1000 / denom`, `CODE_DEFECT_02` e insere `rate <= 0`; LLM devolvendo arquivo inteiro → só a função é enxertada (`1000 / denom` preservado); linha 4 → `ProcessBatchSMS`; handler com `SimulateBug`/`HealthCheck` e rootCause citando `mock-sms-gateway` → `SimulateBug`. |

Não há testes de controller, repositório, scheduler, consumer, storm service, remediation, fanout nem e-mail.

---

## 10. Bugs e comportamentos estranhos (comportamento atual — não corrigir sem decisão)

1. **Branch órfã a cada tentativa de PR** quando o arquivo não é resolvido ou o patch é idêntico (sempre, com LLM `none`) (`svc/agents/CoderAgentService.java:71-91`). Repete a cada varredura enquanto o incidente não for notificado (`svc/AiDiagnosticService.java:76-80,132-140`).
2. **Deploy de qualquer PR mergeado** em repo com webhook: cria linha placeholder e faz `rollout restart` do deployment com o nome do repo em `app.guardian.namespace` (`svc/agents/DeployerAgentService.java:28-50`). Lock ocupado → retorna false e nunca mais tenta (PR sai de `active()`).
3. **MDC vazio**: auditoria de destinatários sempre `SYSTEM`, `correlationId` aleatório; auditoria nunca leva tenant/company (`svc/sre/AlertRecipientUseCaseService.java:45-51`, `out/audit/GuardianAuditPublisher.java:54-61`).
4. **`parse` inválido da IA é gravado como `LLM`**: o fallback marca `heuristicFallback=true` mas o chamador sobrescreve para `false` (`svc/sre/LlmInvestigationService.java:70-74,129-133`).
5. **`incidentId` não é passado na investigação SRE** → `llm_invocations.incident_id` nulo e `X-Correlation-ID` aleatório (`svc/sre/LlmInvestigationService.java:68-69`).
6. **Regras do YAML não chegam ao banco depois do 1º seed** (`infra/classification/ClassificationCatalog.java:26-31,60-63`).
7. **Prompt "versionado" sem histórico**: o refresh sobrescreve a mesma linha trocando `version` (`infra/prompt/CompositePromptCatalog.java:95-106`).
8. **E-mail de prod para exchange `-dev`** (`res/application.yml:130`, sem override em `res/application-prod.yml`).
9. **`provider=anthropic` usa o GatewayLlmAdapter** (condição só exclui ollama/openai, `infra/llm/GatewayLlmAdapter.java:24`) e o starter Anthropic nem está no classpath.
10. **Fingerprint sem namespace** e dependente do tail de logs: o mesmo problema pode virar incidentes diferentes a cada varredura (`svc/AiDiagnosticService.java:180-189`).
11. **Incidente não notificado é reinvestigado a cada varredura** (evidência + 5 sugestões apagadas/recriadas + LLM + pipeline de PR). Sem destinatários configurados, isso é permanente (`svc/AiDiagnosticService.java:70-125`, `svc/sre/AlertRecipientService.java:23-45`).
12. **`forceSendEmail` não fura o cooldown** de 15 min (`svc/sre/AlertFanoutService.java:148-152`).
13. **Watcher**: `errorReason` = phase do pod (quase sempre `Running`), então `CrashLoopBackOff` como errorReason (severidade CRITICAL) nunca vem do watcher (`in/scheduler/KubernetesHealthWatcherScheduler.java:60-63`, `svc/AiDiagnosticService.java:194`); `errorReason` extraído de linha `CODE_DEFECT_` pode passar de 255 chars e quebrar o insert (`:65-68`, `infra/persistence/entity/IncidentJpaEntity.java:40-41`); uma exceção em um pod aborta a varredura inteira e a reconciliação (`:44-88`).
14. **Deployments com `replicas: 0` intencional** são diagnosticados a cada varredura (`out/k8s/KubernetesInspectorService.java:56`) e nunca normalizam (`healthy` exige `desired>0`, `:349`).
15. **OOMKilled no `lastState` marca `crashLoop` para sempre** (até o pod ser substituído) → incidente não normaliza (`out/k8s/KubernetesInspectorService.java:331-336,348-353`). Pods saudáveis com uma `NullPointerException` nas últimas 80 linhas de log também contam como não saudáveis (`:200-208`).
16. **Logs "não disponíveis"** (pod multi-container, pod inexistente `<dep>-deployment`) viram o próprio texto `"Logs não disponíveis: <erro>"` e entram no fingerprint/LLM (`out/k8s/KubernetesInspectorService.java:230-233`).
17. **Status legados** (12 valores antigos + `IGNORED`) contam como "abertos" no fingerprint e nunca são reconciliados (`svc/AiDiagnosticService.java:65`, `svc/sre/IncidentReconciliationService.java:30-37`).
18. **`ACTION_RUNNING` nunca volta para `AWAITING_HUMAN`**; gera `HEALTH_CHECK_FAIL` a cada minuto enquanto indisponível (`svc/sre/IncidentReconciliationService.java:128-133`). Incidentes saudáveis geram `HEALTH_CHECK_PASS` por varredura (`:77-78`); tempestade gera evidência `STORM_ASSESSMENT` por varredura (`svc/sre/ClusterStormService.java:169-175`).
19. **Auditoria do webhook spamada**: `scanOpenPullRequests` chama `onComment` para todos os comentários a cada 45 s e o audit é publicado antes do teste de "já processado" (`svc/pr/HandlePrEventUseCase.java:53-56,86-88`).
20. **`isBotComment`** ignora qualquer autor cujo login contenha `bot` (ex.: `abbott`) (`svc/pr/HandlePrEventUseCase.java:105-109`).
21. **Idempotência do webhook antes do parse**: retry do GitHub de entrega que falhou é ignorado (`ctrl/github/GitHubWebhookController.java:35-37`).
22. **Webhook sem verificação de assinatura** e processamento síncrono (LLM/GitHub dentro do request).
23. **GET `/alert-recipients` escreve** (seed do default) (`svc/sre/AlertRecipientService.java:47-50`). **PATCH ignora email/label**, `enabled` nulo vira `true`, e "não encontrado" é 400 (`svc/sre/AlertRecipientUseCaseService.java:38-43`). Upsert de e-mail existente reabilita sem checar o limite de 20 (`svc/sre/AlertRecipientService.java:59-66`).
24. **Correlation id inconsistente nos alertas**: `sendHtmlTo` recebe `incident.id` como correlationId, enquanto a entrega grava `incident.correlationId`; a auditoria `GUARDIAN_ALERT_*` usa `serviceName` como resourceId (`svc/sre/AlertFanoutService.java:121-137,146-162`, `out/notification/EmailNotificationService.java:61-66`).
25. **HTML da mesa sem escape** (texto da IA/logs vai cru no corpo do e-mail) (`svc/sre/AlertFanoutService.java:174-175`).
26. **E-mails de agente (PR/deploy) vão só para `default-recipient`**, ignorando a tabela de destinatários (`out/notification/EmailNotificationService.java:254`). Três métodos de e-mail estão mortos (`:71,200,224`).
27. **`Map.of` com valor nulo** lança NPE (fallback HTTP com `serviceName` nulo, `out/notification/EmailNotificationService.java:281-283`; `IllegalArgumentException` sem mensagem no handler, `infra/rest/GlobalExceptionHandler.java:17`).
28. **`from`/`to` com offset** têm o offset descartado sem conversão (`infra/persistence/IncidentRepositoryAdapter.java:139-141`); `q` não escapa `%`/`_`; ordenação por `severity`/`status` é alfabética.
29. **`JAVA_METHOD` casa instruções** como `return foo(` ou `else if (` no início da linha, fatiando métodos Java errado (`infra/util/ScopedSourcePatcher.java:16-17`). Preâmbulo Go é descartado apesar do comentário (`:98`).
30. **QA por substring**: `nan` casa `financeiro`, `rate` casa `generate`/`operate` (`svc/agents/QaAutomationAgentService.java:99-109`).
31. **`saveExecution` silencioso**: se o insert falhar (ex.: `X-Correlation-ID` > 64), regrava sem ator/correlação/before/after (`svc/sre/IncidentRemediationService.java:158-166`).
32. **Fallbacks em memória sem TTL** (lock e idempotência) se o Redis cair (`infra/redis/RedisDistributedLockAdapter.java:45-48`, `infra/redis/RedisIdempotencyAdapter.java:29-32`); rate limit com recursão ilimitada (`infra/ratelimit/RateLimiterService.java:43-46`).
33. **Secret OAuth cacheado para sempre** por company (`out/feign/AuthTokenAdapter.java:79-95`).
34. **Timeouts LLM não cancelam o HTTP** (`infra/util/LlmContextLimiter.java:29-49`); Feign do gateway lê até 95 s.
35. **Config morta**: Resilience4j (`res/application.yml:165-187`), `llm-cache-ttl-seconds`, `GuardianProperties.scanIntervalMs/healthyStreakRequired`, `keepguard.audit.source-service`, `app.rabbitmq.exchange/routing-key`, queries `findTopBy*`/`findByNamespace*`/`findByRepoNameAndBranchName`, `isServiceHealthy`, `isClusterHealthy`, `seedIfAbsent`, `CompositePromptCatalog.keys()`.
36. **`updatedAt` possivelmente defasado na resposta** de PUT/PATCH de destinatário: o mapeamento ocorre antes do flush do `@UpdateTimestamp` dentro da transação [framework — validar] (`svc/sre/AlertRecipientService.java:52-86`).
37. **Destinatário "fallback" em memória** tem `id` nulo e não aparece no GET (`svc/sre/AlertRecipientService.java:37-42`).

### Pendências para o doc 02 (não verificáveis só pelo código)
- Dump do schema real `ms_ai_guardian` em prod (tipos, `CHECK` de enums, colunas legadas).
- Env real do Deployment em prod (`APP_RABBITMQ_EMAIL_EXCHANGE`, `APP_GUARDIAN_LLM_PROVIDER`, `APP_GUARDIAN_DEFAULT_RECIPIENT`, `APP_GUARDIAN_TENANT_ID`, credenciais RabbitMQ).
- Quem chama `POST /diagnose*`, `/incidents*` e `/alert-recipients` (BFF/front) e se o webhook do GitHub está configurado em algum repo.
