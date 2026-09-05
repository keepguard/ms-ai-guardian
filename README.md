# ms-ai-guardian

KeepGuard AI Guardian — Diagnóstico Inteligente de Incidentes Kubernetes e Raciocínio SRE.

## LLM Gateway & Operação

A integração com LLMs é centralizada através do `srv-llm-gateway` (porta 8650).

- **Default:** `none` (heurística determinística, sem consumo de tokens).
- **Modo Gateway:** `APP_GUARDIAN_LLM_PROVIDER=gateway`.
- **Egress:** `LLM_GATEWAY_URL=http://srv-llm-gateway:8650`.

Para comandos completos de ativação, desativação e auditoria no Kubernetes em Produção, consulte:
👉 [Documentação Operacional: guardian-llm-ops.md](../../../docs/guardian-llm-ops.md)
