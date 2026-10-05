# Migração Java → Go: ms-ai-guardian

Data: 2026-10-04. Status: **Fase 0 concluída; Fase 2 (implementação) e Fase 3 (testes locais) concluídas
no `ms-ai-guardian-go`. Fase 4 (produção) pendente — roteiro em
`docs/prompts/deploy-ms-ai-guardian-go-prod.md`.** As decisões abaixo foram tomadas pelas recomendações
padrão (ms-company/ms-auth), com autorização do Rafael para implementar tudo de uma vez.
Molde vivo: `ms-auth-go` e `ms-company-go`.

## Por que migrar

| | Uso real em prod |
|---|---|
| ms-ai-guardian (Java) | **638Mi** de RSS (heap ~112 MiB; o resto é JVM), requests 512Mi / limit 1Gi |
| ms-auth-go (já migrado) | 7Mi |

12,6k linhas Java (`src/main`), 9 rotas, 12 tabelas, 1 consumer RabbitMQ, 2 agendadores.

## Documentos

| Arquivo | Conteúdo |
|---|---|
| `01-especificacao.md` | Rotas, contrato de erro, domínio, use cases passo a passo, schema, Redis, config, templates/prompts, testes Java, **37 comportamentos estranhos** (§10). |
| `02-consumidores-e-infra.md` | Só o bff-core chama (6 rotas; lê `message`/`error`; refaz POST em 5xx). Deployment real, RBAC, uso de memória, LLM `none` em prod, webhook não exposto. |
| `03-libs.md` | Não usa lib-common/lib-security/JWT. 12 publicações `GUARDIAN_*` compatíveis com `lib-go-common/audit`. `oauthsecret` cobre o `OAuthSecretCrypto`. |
| `04-resiliencia-integracoes.md` | Resilience4j configurado mas não aplicado (só timeouts Feign). Mapeamento Fabric8 → REST. Fila com retry 3x + DLQ. |
| `05-arquitetura-alvo-go.md` | Estrutura Go, contextos, divisão do trabalho e checklist do corte. |

## O que o levantamento mostrou

1. **O que roda de fato em prod** é o watcher (60 s), a classificação heurística, os incidentes e a mesa
   (sugestões, ações, ciclo de vida), os destinatários, e-mail e auditoria por RabbitMQ e Redis.
   `/diagnose`, `/diagnose/async`, a fila, o webhook, os agentes de PR e o LLM estão **sem uso real**
   (LLM `none`, webhook sem Ingress, `pull_request_lifecycles` vazia, fila nunca publicada).
2. **O contrato de erro não é ProblemDetail:** `{"error","message"}` e o corpo padrão `/error` do Spring.
   O bff-core lê `message`/`error`.
3. **Nada de resiliência ativa** além dos timeouts do Feign; o Go aplica timeout + breaker (`resilient`).
4. **Achados fora do escopo** (registrados, não mudam nesta migração): e-mails publicados no exchange
   `-dev` sem consumidor desde 2026-09-27 (o srv-email-sender escuta `-local`); `GITHUB_TOKEN` literal no
   Deployment; `APP_GUARDIAN_TENANT_ID` vazio (fallback ms-communication inviável); rotas sem autenticação
   e webhook sem verificação de assinatura; incidente fantasma `ms-company-deployment` gera WARN por minuto.

## Decisões

| # | Decisão | Tomada |
|---|---|---|
| D1 | Paridade de rotas | **Sim, 9 rotas** (teste de roteamento compara com o Java), inclusive diagnose/async/webhook. |
| D2 | Lib | **`lib-go-common v0.2.0` por tag + `vendor/`**. Nada novo na lib: o cliente OAuth client_credentials fica no serviço (`out/http/msauth`). |
| D3 | IA | **Sem framework de IA.** Só o caminho `gateway` (HTTP no srv-llm-gateway + token do ms-auth). Spring AI direto (Ollama/OpenAI/Anthropic) **não é portado**: desligado em prod. |
| D4 | Kubernetes | **REST cru** (sem client-go): token/CA do ServiceAccount; mesmo RBAC. Scale por PATCH em `spec.replicas`. |
| D5 | Transação | Como o Java: cada save é atômico; transação só onde havia `@Transactional` (investigação = evidência + troca de sugestões; destinatários). Chamadas externas fora de transação. |
| D6 | Bugs — lista fechada | (a) erro de framework (UUID/JSON inválido) = 400 no corpo padrão (como o Spring); (b) **o CoderAgent só cria a branch depois de ter um patch diferente** — o Java criava a branch antes e deixava `fix/guardian-*` órfã a cada varredura com LLM `none`. O resto (37 itens do `01` §10) fica igual e documentado. |
| D7 | Métricas | `http_server_requests_*` por rota, `api_requests_*` por endpoint (nome do método do controller), estado dos breakers. Sem rótulo de alta cardinalidade. O Java não tinha métrica de negócio. |
| D8 | Rate limiter | **Portado** (GitHub 10/s, LLM 5/s, e-mail 20/s): no Java ele era efetivo (Redis INCR + Bucket4j local). |
| D9 | Convivência | Go sobe ao lado com watcher, varredura de PR e consumer **desligados** (Recreate + um só dono de cada laço). Liga tudo no corte. |
| D10 | Contratos fora do HTTP | Mesmas chaves Redis/TTLs e mesmo JSON (lock, cooldown e storm são compartilhados durante a convivência); mesmos nomes de fila/exchange e argumentos da fila (DLX). |
| D11 | Segredos | `GITHUB_TOKEN` e destinatário padrão do secret `ms-ai-guardian-secret`; RabbitMQ do configmap/secret (não `guest/guest`). |
| D12 | Exchange de e-mail | **Paridade (`-dev`)** — o defeito está no srv-email-sender; corrigir lá (item próprio). Configurável por `APP_RABBITMQ_EMAIL_EXCHANGE`. |
| D13 | Corte | Selector do Service `ms-ai-guardian` → `app=ms-ai-guardian-go`; Java em 0 réplicas por alguns dias. |

## Status da implementação

- Código: `keepguard-core/backend/ms/ms-ai-guardian-go` (repo `keepguard/ms-ai-guardian-go`).
- Testes locais: unitários + API ponta a ponta contra Postgres/Redis/RabbitMQ reais (ver
  `ms-ai-guardian-go/docs/testes-locais.md`).
- Produção: pendente, com confirmação do Rafael em cada passo.
