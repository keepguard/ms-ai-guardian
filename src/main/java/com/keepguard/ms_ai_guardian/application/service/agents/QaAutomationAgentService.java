package com.keepguard.ms_ai_guardian.application.service.agents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class QaAutomationAgentService {

    public QaCertificationReport certifyQuality(String serviceName, String targetFile, String fixedCode) {
        return certifyQuality(serviceName, targetFile, null, fixedCode, null);
    }

    public QaCertificationReport certifyQuality(String serviceName, String targetFile, String fixedCode,
            String errorReason) {
        return certifyQuality(serviceName, targetFile, null, fixedCode, errorReason);
    }

    public QaCertificationReport certifyQuality(String serviceName, String targetFile, String originalCode,
            String fixedCode, String errorReason) {
        log.info("🧪 [QaAutomationAgent] Validando hotfix no escopo do incidente ({}) para {}", errorReason, serviceName);

        List<TestCaseResult> testCases = new ArrayList<>();
        String reason = errorReason != null ? errorReason.toLowerCase(Locale.ROOT) : "";
        boolean changed = originalCode != null && fixedCode != null && !originalCode.equals(fixedCode);

        String inScopeName;
        String inScopeDesc;
        boolean inScopePass;
        if (isArithmeticIncident(reason)) {
            inScopeName = "TC-01: Guarda contra divisão / valor inválido";
            inScopeDesc = "O fluxo do incidente trata denominador/rate inválido";
            inScopePass = hasNumericGuard(fixedCode);
        } else if (isNullIncident(reason)) {
            inScopeName = "TC-01: Guarda contra nulo";
            inScopeDesc = "O fluxo do incidente valida ponteiro/objeto ausente";
            inScopePass = hasNullGuard(fixedCode);
        } else if (isBoundsIncident(reason)) {
            inScopeName = "TC-01: Guarda de limites";
            inScopeDesc = "O fluxo do incidente valida índice/tamanho";
            inScopePass = hasBoundsGuard(fixedCode);
        } else {
            inScopeName = "TC-01: Alteração no fluxo do incidente";
            inScopeDesc = "Há diferença no arquivo alvo para o motivo do incidente";
            inScopePass = changed;
        }

        testCases.add(new TestCaseResult(
                inScopeName,
                inScopeDesc,
                "Hotfix cobre o erro reportado",
                inScopePass ? TestStatus.PASSED : TestStatus.FAILED
        ));

        boolean surgical = originalCode == null || isSurgical(originalCode, fixedCode);
        testCases.add(new TestCaseResult(
                "TC-02: Patch pontual (não reescreve a classe)",
                "Maior parte das linhas originais permanece",
                "Diff isolado ao fluxo do incidente",
                originalCode == null ? TestStatus.OUT_OF_SCOPE
                        : (surgical && changed ? TestStatus.PASSED : TestStatus.FAILED)
        ));

        testCases.add(new TestCaseResult(
                "TC-03: Contratos e fluxos fora do incidente",
                "Demais métodos / cenários do arquivo",
                "Preservados (observação)",
                TestStatus.OUT_OF_SCOPE
        ));

        boolean hotfixOk = testCases.stream()
                .filter(t -> t.status() != TestStatus.OUT_OF_SCOPE)
                .allMatch(t -> t.status() == TestStatus.PASSED);

        log.info("🧪 [QaAutomationAgent] Hotfix no escopo: {} ({}/{} in-scope PASS)",
                hotfixOk ? "APROVADO" : "REPROVADO",
                testCases.stream().filter(t -> t.status() == TestStatus.PASSED).count(),
                testCases.stream().filter(t -> t.status() != TestStatus.OUT_OF_SCOPE).count()
        );

        return new QaCertificationReport(
                hotfixOk,
                hotfixOk
                        ? "QA DO HOTFIX: APROVADO NO ESCOPO DO INCIDENTE (demais casos = observação)"
                        : "QA DO HOTFIX: REPROVADO — o incidente não foi coberto pelos cheques in-scope",
                testCases
        );
    }

    static boolean isArithmeticIncident(String reason) {
        return containsAny(reason, "divis", "zero", "arithmetic", "overflow", "nan", "tarif", "rate");
    }

    static boolean isNullIncident(String reason) {
        return containsAny(reason, "null", "npe", "nil", "pointer", "npe");
    }

    static boolean isBoundsIncident(String reason) {
        return containsAny(reason, "index", "bounds", "slice", "array", "range", "underflow");
    }

    static boolean hasNumericGuard(String code) {
        if (code == null) {
            return false;
        }
        return code.contains("<= 0") || code.contains("== 0") || code.contains("< 1")
                || code.contains("<=0") || code.contains("==0");
    }

    static boolean hasNullGuard(String code) {
        if (code == null) {
            return false;
        }
        return code.contains("!= null") || code.contains("== null") || code.contains("!= nil")
                || code.contains("== nil") || code.contains("Optional");
    }

    static boolean hasBoundsGuard(String code) {
        if (code == null) {
            return false;
        }
        return code.contains("len(") || code.contains(".length") || code.contains("size()")
                || code.contains(" < 0") || code.contains(">= len") || code.contains(">= length");
    }

    static boolean isSurgical(String original, String fixed) {
        if (original == null || fixed == null || original.equals(fixed)) {
            return false;
        }
        Set<String> origLines = new HashSet<>(Arrays.asList(original.split("\\R")));
        origLines.removeIf(String::isBlank);
        if (origLines.isEmpty()) {
            return true;
        }
        Set<String> fixedLines = new HashSet<>(Arrays.asList(fixed.split("\\R")));
        long kept = origLines.stream().filter(fixedLines::contains).count();
        return kept >= origLines.size() * 0.55;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    public enum TestStatus {
        PASSED,
        FAILED,
        OUT_OF_SCOPE
    }

    public record TestCaseResult(
            String testName,
            String description,
            String expectedResult,
            TestStatus status
    ) {}

    public record QaCertificationReport(
            boolean certified,
            String verdictText,
            List<TestCaseResult> testCases
    ) {
        public String toMarkdownTable() {
            StringBuilder sb = new StringBuilder();
            sb.append("| Caso de Teste | Descrição | Resultado Esperado | Status |\n");
            sb.append("| :--- | :--- | :--- | :---: |\n");
            for (var tc : testCases) {
                String statusIcon = switch (tc.status()) {
                    case PASSED -> "✅ PASSOU";
                    case FAILED -> "❌ FALHOU";
                    case OUT_OF_SCOPE -> "⏭️ FORA DO ESCOPO";
                };
                sb.append(String.format("| `%s` | %s | %s | **%s** |\n",
                        tc.testName(), tc.description(), tc.expectedResult(), statusIcon));
            }
            return sb.toString();
        }
    }
}
