package com.keepguard.ms_ai_guardian.domain.classification;

import com.keepguard.ms_ai_guardian.domain.enums.ClassificationVerdict;

public record BusinessVerdict(
        ClassificationVerdict type,
        String summary,
        String businessContext,
        String suggestedSqlAction,
        boolean requiresCodePr
) {
    public static BusinessVerdict codeDefect(String errorReason) {
        String reason = errorReason == null || errorReason.isBlank() ? "defeito de código" : errorReason;
        return new BusinessVerdict(
                ClassificationVerdict.CODE_DEFECT,
                "Defeito de implementação de código identificado (" + reason + ")",
                "A falha decorre de uma exceção não tratada na lógica da aplicação. Não há evidência de inconsistência cadastral.",
                "",
                true
        );
    }
}
