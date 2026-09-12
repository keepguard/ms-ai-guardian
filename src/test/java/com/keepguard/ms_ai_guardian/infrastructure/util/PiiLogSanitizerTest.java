package com.keepguard.ms_ai_guardian.infrastructure.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiLogSanitizer - Higienização de Logs para LLM")
class PiiLogSanitizerTest {

    @Test
    @DisplayName("Deve mascarar email, cpf, token e telefone em stacktrace de log")
    void shouldSanitizeAllPiiFromLogs() {
        String rawLog = "2026-09-10 12:00:00 ERROR c.k.ms_user.RegisterService - Falha no cadastro: user=teste@empresa.com, "
                + "cpf=123.456.789-00, phone=+55 11 99999-1234, token=abc123xyzSecret, Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

        String sanitized = PiiLogSanitizer.sanitize(rawLog);

        assertThat(sanitized).doesNotContain("teste@empresa.com");
        assertThat(sanitized).doesNotContain("123.456.789-00");
        assertThat(sanitized).doesNotContain("99999-1234");
        assertThat(sanitized).doesNotContain("abc123xyzSecret");
        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");

        assertThat(sanitized).contains("[REDACTED_EMAIL]");
        assertThat(sanitized).contains("[REDACTED_CPF]");
        assertThat(sanitized).contains("[REDACTED_PHONE]");
        assertThat(sanitized).contains("[REDACTED_SECRET]");
    }

    @Test
    @DisplayName("Deve lidar com null e string vazia sem lançar exceção")
    void shouldHandleNullAndEmptyGracefully() {
        assertThat(PiiLogSanitizer.sanitize(null)).isNull();
        assertThat(PiiLogSanitizer.sanitize("")).isEqualTo("");
        assertThat(PiiLogSanitizer.sanitize("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("Cenário de aceite da spec: CrashLoopBackOff com stacktrace")
    void shouldMeetSpecificationScenario() {
        String logSnippet = "pod ms-user CrashLoopBackOff: user=teste@empresa.com, cpf=123.456.789-00, token=abc123xyz";
        String sanitized = PiiLogSanitizer.sanitize(logSnippet);

        assertThat(sanitized).contains("[REDACTED_EMAIL]");
        assertThat(sanitized).contains("[REDACTED_CPF]");
        assertThat(sanitized).contains("[REDACTED_SECRET]");
        assertThat(sanitized).doesNotContain("teste@empresa.com");
        assertThat(sanitized).doesNotContain("123.456.789-00");
    }
}
