## 🛡️ KeepGuard AI Guardian - Hotfix Automatizado & Squad Multi-Agent

### 📋 Detalhes do Incidente
- **Serviço Afetado:** `{{serviceName}}`
- **Pod:** `{{podName}}`
- **Severidade:** `{{severity}}`
- **Motivo:** `{{errorReason}}`
- **Arquivo Modificado:** `{{filePath}}`

### 🔍 Causa Raiz Diagnosticada
> {{rootCause}}

### 💡 Ação Aplicada pelo CoderAgent
Hotfix **pontual** no fluxo do incidente (não é refactor da classe).
> {{action}}

---
{{businessSection}}
---
{{archSection}}
---
{{qaSection}}
---
### 🚦 Fluxo de Aprovação e Quality Gate
1. 🤖 **ReviewerAgent:** Validará a segurança e conformidade arquitetural.
2. 👤 **Humano (@{{approverGithub}}):** Deve revisar o diff e realizar o **Merge** final.
3. 🚀 **DeployerAgent:** Realizará o rollout automático no Kubernetes pós-Merge.
