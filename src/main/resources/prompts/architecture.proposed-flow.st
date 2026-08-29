```mermaid
sequenceDiagram
    autonumber
    actor Cliente
    participant App as {{serviceName}}
    participant Alvo as `{{file}}`

    Cliente->>App: Requisição
    App->>Alvo: Executa fluxo com guarda defensiva
    Note over Alvo: ✅ Hotfix pontual do incidente
    Alvo-->>App: Sucesso
    App-->>Cliente: Resposta OK
```
