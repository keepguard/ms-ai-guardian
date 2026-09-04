package com.keepguard.ms_ai_guardian.infrastructure.i18n;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;

import java.util.Locale;
import java.util.Map;

/**
 * Rótulos em português para textos persistidos (resumo da IA, e-mails, ciclo de vida).
 * Códigos técnicos continuam no banco para filtro e fingerprint.
 */
public final class GuardianPortuguese {

    public static final String NARRATIVE_LANGUAGE_RULE = """
            Idioma obrigatório: os campos narrativos da resposta (rootCause, summary, riskNotes e qualquer parecer) \
            DEVEM estar em português brasileiro. Não escreva esses campos em inglês. \
            recommendedActionIds e nomes de recursos Kubernetes permanecem com os códigos técnicos.
            """;

    private static final Map<String, String> ERROR_REASONS = Map.ofEntries(
            Map.entry("SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE", "Indisponibilidade do serviço: nenhuma réplica disponível"),
            Map.entry("RESTART_OR_FAILURE_DETECTED", "Reinício ou falha detectada no pod"),
            Map.entry("PANIC_RUNTIME_EXCEPTION", "Exceção de runtime (panic)"),
            Map.entry("NULLPOINTEREXCEPTION", "Exceção de ponteiro nulo (NullPointerException)"),
            Map.entry("CLUSTER_WIDE_OUTAGE", "Indisponibilidade generalizada do cluster"),
            Map.entry("MANUAL_TRIGGER", "Diagnóstico manual"),
            Map.entry("MANUAL_ASYNC_TRIGGER", "Diagnóstico manual assíncrono"),
            Map.entry("CRASHLOOPBACKOFF", "Container reiniciando em loop (CrashLoopBackOff)"),
            Map.entry("PENDING", "Pod pendente (não agendado)"),
            Map.entry("FAILED", "Pod falhou"),
            Map.entry("UNKNOWN", "Estado do pod desconhecido"),
            Map.entry("RUNNING", "Pod em execução, porém não saudável"),
            Map.entry("SUCCEEDED", "Pod concluído")
    );

    private static final Map<String, String> K8S_CONCLUSIONS = Map.ofEntries(
            Map.entry("CONTROLLER_ALREADY_RETRYING", "Controlador já está tentando novamente"),
            Map.entry("REPLICAS_INTENTIONALLY_ZERO", "Réplicas zeradas de propósito"),
            Map.entry("UNSCHEDULABLE", "Pod não agendável"),
            Map.entry("IMAGE_OR_CONFIG", "Falha de imagem ou configuração"),
            Map.entry("NODE_FAILURE", "Falha de nó"),
            Map.entry("NO_CONTROLLER", "Sem controlador (Deployment/ReplicaSet)"),
            Map.entry("TRANSIENT_INFRA_RECOVERABLE", "Infraestrutura transitória, recuperável"),
            Map.entry("UNKNOWN", "Conclusão desconhecida")
    );

    private static final Map<String, String> STORM_REASONS = Map.of(
            "NODE_NOT_READY", "Nó não pronto",
            "MASS_DEPLOYMENT_UNAVAILABLE", "Vários deployments indisponíveis",
            "NONE", "Sem tempestade"
    );

    private static final Map<String, String> WAITING_REASONS = Map.ofEntries(
            Map.entry("CRASHLOOPBACKOFF", "Container reiniciando em loop"),
            Map.entry("IMAGEPULLBACKOFF", "Falha ao baixar a imagem (nova tentativa)"),
            Map.entry("ERRIMAGEPULL", "Falha ao baixar a imagem"),
            Map.entry("CREATECONTAINERCONFIGERROR", "Erro de configuração ao criar o container"),
            Map.entry("CREATECONTAINERERROR", "Erro ao criar o container"),
            Map.entry("INVALIDIMAGENAME", "Nome de imagem inválido"),
            Map.entry("RUNCONTAINERERROR", "Erro ao iniciar o container"),
            Map.entry("CONTAINERCREATING", "Container em criação"),
            Map.entry("PODINITIALIZING", "Pod em inicialização"),
            Map.entry("UNSCHEDULABLE", "Não agendável"),
            Map.entry("NODENOTREADY", "Nó não pronto"),
            Map.entry("UNEXPECTEDADMISSIONERROR", "Erro inesperado na admissão do pod")
    );

    private GuardianPortuguese() {}

    public static String errorReason(String code) {
        if (blank(code)) {
            return "anomalia";
        }
        String mapped = ERROR_REASONS.get(norm(code));
        if (mapped != null) {
            return mapped;
        }
        if (code.toUpperCase(Locale.ROOT).startsWith("CODE_DEFECT_")) {
            return "Defeito de código: " + code.substring("CODE_DEFECT_".length()).replace('_', ' ');
        }
        return humanize(code);
    }

    public static String k8sConclusion(K8sConclusion conclusion) {
        return conclusion == null ? K8S_CONCLUSIONS.get("UNKNOWN") : k8sConclusion(conclusion.name());
    }

    public static String k8sConclusion(String code) {
        if (blank(code)) {
            return "sem conclusão K8s";
        }
        String mapped = K8S_CONCLUSIONS.get(norm(code));
        return mapped != null ? mapped : humanize(code);
    }

    public static String stormReason(String code) {
        if (blank(code)) {
            return "motivo não informado";
        }
        String mapped = STORM_REASONS.get(norm(code));
        return mapped != null ? mapped : humanize(code);
    }

    public static String waitingReason(String code) {
        if (blank(code)) {
            return "nenhum";
        }
        String mapped = WAITING_REASONS.get(norm(code));
        return mapped != null ? mapped : humanize(code);
    }

    public static String phase(String code) {
        return errorReason(code);
    }

    public static String severity(IncidentSeverity severity) {
        if (severity == null) {
            return "não informada";
        }
        return switch (severity) {
            case CRITICAL -> "Crítica";
            case HIGH -> "Alta";
            case MEDIUM -> "Média";
            case LOW -> "Baixa";
            case INFO -> "Informativa";
        };
    }

    static String humanize(String code) {
        String trimmed = code.trim();
        if (trimmed.indexOf(' ') >= 0) {
            return trimmed;
        }
        return trimmed.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
    }

    private static String norm(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
