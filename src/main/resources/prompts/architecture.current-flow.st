```mermaid
sequenceDiagram
    autonumber
    actor Cliente
    participant App as {{serviceName}}
    participant Alvo as `{{file}}`

    Cliente->>App: Requisição
    App->>Alvo: Executa fluxo
    Note over Alvo: ❌ {{error}}
    Alvo--xApp: Falha
    App--xCliente: Erro
```
