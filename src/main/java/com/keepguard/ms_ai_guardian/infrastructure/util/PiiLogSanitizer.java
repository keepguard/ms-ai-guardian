package com.keepguard.ms_ai_guardian.infrastructure.util;

import java.util.regex.Pattern;

/**
 * Sanitizador de dados pessoais (PII) e segredos para logs do Kubernetes
 * antes do envio para modelos de linguagem (LLM) externos.
 * Atende ao Gap 2 do Plano de Ação LGPD.
 */
public final class PiiLogSanitizer {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CPF_FORMATTED_PATTERN = Pattern.compile(
            "\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b"
    );

    private static final Pattern CPF_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:cpf|documento)\\s*[:=]\\s*['\"]?(\\d{11})['\"]?"
    );

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?55\\s?)?(?:\\(?\\d{2}\\)?\\s?)?\\d{4,5}[-\\s]?\\d{4}"
    );

    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "Bearer\\s+[A-Za-z0-9-_.]+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"
    );

    private static final Pattern SECRET_KEYVALUE_PATTERN = Pattern.compile(
            "(?i)(token|password|secret|senha|access_token|refresh_token)\\s*[:=]\\s*['\"]?([^,\\s'\"\\n]+)['\"]?"
    );

    private PiiLogSanitizer() {
    }

    /**
     * Higieniza o texto bruto de logs, substituindo PIIs e credenciais por marcadores neutros.
     *
     * @param rawLogs snippet de logs brutos
     * @return snippet sanitizado livre de dados pessoais e segredos
     */
    public static String sanitize(String rawLogs) {
        if (rawLogs == null || rawLogs.isBlank()) {
            return rawLogs;
        }

        String result = rawLogs;

        // 1. Sanitizar JWTs
        result = JWT_PATTERN.matcher(result).replaceAll("[REDACTED_SECRET]");

        // 2. Sanitizar Bearer tokens
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [REDACTED_SECRET]");

        // 3. Sanitizar chaves e tokens de autenticação key=value
        result = SECRET_KEYVALUE_PATTERN.matcher(result).replaceAll("$1=[REDACTED_SECRET]");

        // 4. Sanitizar CPFs formatados e contextuais
        result = CPF_FORMATTED_PATTERN.matcher(result).replaceAll("[REDACTED_CPF]");
        result = CPF_CONTEXT_PATTERN.matcher(result).replaceAll("cpf=[REDACTED_CPF]");

        // 5. Sanitizar E-mails
        result = EMAIL_PATTERN.matcher(result).replaceAll("[REDACTED_EMAIL]");

        // 6. Sanitizar Telefones (após emails e CPFs para evitar falsos positivos)
        result = PHONE_PATTERN.matcher(result).replaceAll("[REDACTED_PHONE]");

        return result;
    }
}
