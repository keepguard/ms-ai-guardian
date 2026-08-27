package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessAnalystAgentService {

    private final EmailNotificationService emailNotificationService;
    private final Optional<ChatClient.Builder> chatClientBuilder;
    private final Optional<JdbcTemplate> jdbcTemplate;

    /**
     * Analisa o contexto do incidente para determinar se é uma falha de REGRA DE NEGÓCIO / BANCO DE DADOS
     * ou um DEFEITO DE CÓDIGO (bug no software).
     */
    public BusinessVerdict evaluateIncident(DiagnosticResultDTO incident, String recentLogs) {
        log.info("👔 [BusinessAnalystAgent] Analisando impacto e consistência de negócio para: {} (Erro: {})",
                incident.getServiceName(), incident.getErrorReason());

        String errorLower = incident.getErrorReason() != null ? incident.getErrorReason().toLowerCase() : "";
        String logsLower = recentLogs != null ? recentLogs.toLowerCase() : "";

        // 0. Verificação de Falhas de Infraestrutura / Kubernetes / Deployment / Imagem / Pod LifeCycle
        if (errorLower.contains("pending") || errorLower.contains("imagepullbackoff") 
                || errorLower.contains("errimagepull") || errorLower.contains("containercreating")
                || errorLower.contains("createcontainerconfigerror") || errorLower.contains("oomkilled")
                || errorLower.contains("service_outage_zero_replicas")
                || logsLower.contains("failed to pull and unpack image") || logsLower.contains("no match for platform in manifest")
                || logsLower.contains("connection refused") || logsLower.contains("dial tcp")) {

            String infraExplanation = """
                ⚙️ **Incidente de Infraestrutura / Deploy / Orquestração Kubernetes**:
                O Pod/Container apresentou anomalia a nível de cluster, imagem Docker ou agendamento (ex: %s).
                Não há evidências de defeito na lógica interna da aplicação. Esta falha requer intervenção SRE/DevOps.
                """.formatted(incident.getErrorReason());

            return new BusinessVerdict(
                    VerdictType.INFRASTRUCTURE_FAULT,
                    "Falha operacional de infraestrutura / deploy (" + incident.getErrorReason() + ")",
                    infraExplanation,
                    "-- Inspecionar kubectl describe pod / imagens no GHCR / recursos do nó",
                    false // NUNCA deve abrir PR de código!
            );
        }

        // 1. Verificação de Inconsistência de Dados Cadastrais / Falha Operacional de Banco
        if (logsLower.contains("autenticar tenant") || logsLower.contains("empresa/tenant não encontrado") 
                || logsLower.contains("auth_tenant_not_found") || errorLower.contains("tenant not found")) {
            
            String businessExplanation = """
                ❌ **Falha de Cadastro / Inconsistência de Dados**:
                A API Key enviada na requisição não foi encontrada na tabela `companies` do banco de dados `mock_test`.
                Este não é um defeito de código no serviço Go, mas sim um tenant/empresa não provisionada ou chave expirada.
                """;

            String suggestedSql = """
                -- Script de Correção Operacional (Inserir Empresa Ativa):
                INSERT INTO companies (id, api_key, name, status, sms_balance, tier)
                VALUES ('comp_nova_01', 'key_enviada_no_payload', 'Empresa Cliente Telecom', 'ACTIVE', 5000, 'ENTERPRISE')
                ON CONFLICT (id) DO NOTHING;
                """;

            return new BusinessVerdict(
                    VerdictType.DATA_INCONSISTENCY,
                    "Tenant / API Key não cadastrada na tabela companies (mock_test)",
                    businessExplanation,
                    suggestedSql,
                    false // NÃO deve abrir PR de código!
            );
        }

        if (logsLower.contains("empresa está bloqueada") || logsLower.contains("tenant_blocked") || errorLower.contains("blocked")) {
            String businessExplanation = """
                ⚠️ **Violação de Regra de Negócio (Inadimplência / Bloqueio)**:
                A empresa associada à requisição está marcada como `BLOCKED` na tabela `companies`. O serviço rejeitou o lote corretamente conforme as políticas de crédito corporativo.
                """;

            String suggestedSql = """
                -- Script de Regularização de Cadastro:
                UPDATE companies SET status = 'ACTIVE' WHERE api_key = 'key_empresa_inadimplente';
                """;

            return new BusinessVerdict(
                    VerdictType.BUSINESS_RULE_VIOLATION,
                    "Empresa bloqueada por regra de crédito ou segurança",
                    businessExplanation,
                    suggestedSql,
                    false // NÃO deve abrir PR de código!
            );
        }

        if (logsLower.contains("saldo de créditos sms insuficiente") || logsLower.contains("insufficient_credits")) {
            String businessExplanation = """
                💰 **Saldo de Créditos Excedido**:
                A empresa não possui saldo de SMS suficiente para processar a quantidade de mensagens do lote. O bloqueio é esperado pelo domínio financeiro.
                """;

            String suggestedSql = """
                -- Recarga de Créditos SMS Corporativos:
                UPDATE companies SET sms_balance = sms_balance + 10000 WHERE id = 'comp_vivo_corp_01';
                """;

            return new BusinessVerdict(
                    VerdictType.BUSINESS_RULE_VIOLATION,
                    "Saldo de créditos SMS insuficiente no banco mock_test",
                    businessExplanation,
                    suggestedSql,
                    false // NÃO deve abrir PR de código!
            );
        }

        if (logsLower.contains("nenhuma rota de operadora") || logsLower.contains("no_carrier_route")) {
            String businessExplanation = """
                📡 **Inconsistência na Matriz de Roteamento Telecom**:
                Não existe nenhuma operadora ativa (`carrier_routes`) vinculada ao nível de prioridade solicitado pela empresa.
                """;

            String suggestedSql = """
                -- Inserir Rota de Operadora:
                INSERT INTO carrier_routes (id, company_id, priority, carrier_name, cost_per_sms, is_active)
                VALUES ('route_emergencia', 'comp_vivo_corp_01', 'ENTERPRISE_GOLD', 'VIVO_DIRECT', 10, TRUE);
                """;

            return new BusinessVerdict(
                    VerdictType.DATA_INCONSISTENCY,
                    "Matriz de roteamento sem operadora ativa para a prioridade solicitada",
                    businessExplanation,
                    suggestedSql,
                    false // NÃO deve abrir PR de código!
            );
        }

        if (errorLower.contains("panic") || errorLower.contains("code_defect")
                || errorLower.contains("nullpointer")
                || logsLower.contains("panic recover") || logsLower.contains("code_defect_")
                || logsLower.contains("panic_runtime")) {
            return new BusinessVerdict(
                    VerdictType.CODE_DEFECT,
                    "Defeito de implementação de código identificado (" + incident.getErrorReason() + ")",
                    "A falha decorre de uma exceção não tratada na lógica da aplicação (panic / NPE / CODE_DEFECT). Não há evidência de inconsistência cadastral.",
                    "",
                    true
            );
        }

        // 2. Análise com LLM (Se disponível) para casos complexos
        if (chatClientBuilder.isPresent()) {
            try {
                String prompt = String.format("""
                    Você é um Business Analyst e Product Owner especialista em Microsserviços e Domínios Corporativos.
                    Analise o erro abaixo:
                    Serviço: %s
                    Erro: %s
                    Logs: %s
                    
                    Classifique se é 'DATA_INCONSISTENCY' (dado nulo/inválido no banco) ou 'CODE_DEFECT' (bug no código que requer hotfix).
                    Responda em no máximo 8 linhas.
                    """, incident.getServiceName(), incident.getErrorReason(),
                    com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter.tail(recentLogs, 1500));

                String aiResult = com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter.callWithTimeout(
                        () -> chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content(),
                        15,
                        null);
                if (aiResult != null && aiResult.contains("DATA_INCONSISTENCY")) {
                    return new BusinessVerdict(VerdictType.DATA_INCONSISTENCY, "Inconsistência de Dados de Negócio", aiResult, "-- Inspecionar tabelas relacionais", false);
                }
            } catch (Exception e) {
                log.warn("Falha no LLM do BusinessAnalystAgent: {}", e.getMessage());
            }
        }

        // 3. Caso padrão: Trata-se de um defeito real no código-fonte que requer hotfix
        return new BusinessVerdict(
                VerdictType.CODE_DEFECT,
                "Defeito de implementação de código identificado",
                "A falha decorre de uma vulnerabilidade ou exceção não tratada na lógica de programação da aplicação.",
                "",
                true // DEVE abrir PR de código!
        );
    }

    public enum VerdictType {
        INFRASTRUCTURE_FAULT,
        DATA_INCONSISTENCY,
        BUSINESS_RULE_VIOLATION,
        CODE_DEFECT
    }

    public record BusinessVerdict(
            VerdictType type,
            String summary,
            String businessContext,
            String suggestedSqlAction,
            boolean requiresCodePr
    ) {}
}
