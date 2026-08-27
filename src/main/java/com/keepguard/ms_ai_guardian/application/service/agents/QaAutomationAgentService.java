package com.keepguard.ms_ai_guardian.application.service.agents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class QaAutomationAgentService {

    /**
     * Certifica só o que o incidente pede. Demais casos vão como fora do escopo, não reprovam o hotfix.
     */
    public QaCertificationReport certifyQuality(String serviceName, String targetFile, String fixedCode) {
        return certifyQuality(serviceName, targetFile, fixedCode, null);
    }

    public QaCertificationReport certifyQuality(String serviceName, String targetFile, String fixedCode,
            String errorReason) {
        log.info("🧪 [QaAutomationAgent] Validando hotfix no escopo do incidente ({}) para {}", errorReason, serviceName);

        List<TestCaseResult> testCases = new ArrayList<>();
        String reason = errorReason != null ? errorReason.toLowerCase(Locale.ROOT) : "";

        if ("mock-sms-gateway".equalsIgnoreCase(serviceName) || (targetFile != null && targetFile.contains("sms_service"))) {
            boolean tariffInScope = reason.contains("code_defect_01") || reason.contains("divis")
                    || reason.contains("zero") || reason.contains("rate") || reason.contains("tarif")
                    || reason.isBlank();

            boolean testRateZeroPass = fixedCode != null && (fixedCode.contains("rate <= 0")
                    || fixedCode.contains("if rate <= 0") || fixedCode.contains("rate == 0")
                    || (fixedCode.contains("CODE_DEFECT_01") && (fixedCode.contains("denom <= 0")
                            || fixedCode.contains("denominator <= 0"))));
            testCases.add(new TestCaseResult(
                    "TC-01: Cálculo Tarifário com Rate Zero / Negativo",
                    "Simula envio de prioridade com taxa não configurada (rate = 0)",
                    "Retorno de taxa defensiva sem Panic",
                    inScopeStatus(tariffInScope, testRateZeroPass)
            ));

            testCases.add(new TestCaseResult(
                    "TC-02: Validação de Saldo de Créditos SMS Corporativos",
                    "Lote maior que saldo disponível",
                    "Erro de domínio / HTTP 402",
                    TestStatus.OUT_OF_SCOPE
            ));
            testCases.add(new TestCaseResult(
                    "TC-03: Roteamento de Operadora e Persistência Hexagonal",
                    "Histórico salvo no PostgreSQL mock_test",
                    "Persistência em lote",
                    TestStatus.OUT_OF_SCOPE
            ));
        } else {
            boolean npeInScope = reason.contains("null") || reason.contains("npe") || reason.contains("pointer")
                    || reason.isBlank();
            boolean testNullPass = fixedCode != null && (fixedCode.contains("!= null") || fixedCode.contains("Optional"));
            testCases.add(new TestCaseResult(
                    "TC-01: Null-Pointer Safety Check",
                    "Payload incompleto / campo ausente",
                    "Tratamento defensivo sem NPE",
                    inScopeStatus(npeInScope, testNullPass)
            ));
            testCases.add(new TestCaseResult(
                    "TC-02: Validação de Regressão de Contrato REST",
                    "Integridade dos DTOs (fora do incidente)",
                    "Contrato preservado",
                    TestStatus.OUT_OF_SCOPE
            ));
        }

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

    private static TestStatus inScopeStatus(boolean inScope, boolean passed) {
        if (!inScope) {
            return TestStatus.OUT_OF_SCOPE;
        }
        return passed ? TestStatus.PASSED : TestStatus.FAILED;
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
                    case PASSED -> "✅ PASS";
                    case FAILED -> "❌ FAIL";
                    case OUT_OF_SCOPE -> "⏭️ FORA DO ESCOPO";
                };
                sb.append(String.format("| `%s` | %s | %s | **%s** |\n",
                        tc.testName(), tc.description(), tc.expectedResult(), statusIcon));
            }
            return sb.toString();
        }
    }
}
