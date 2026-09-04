package com.keepguard.ms_ai_guardian.infrastructure.i18n;

import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardianPortugueseTest {

    @Test
    void translatesKnownErrorReasons() {
        assertEquals("Indisponibilidade do serviço: nenhuma réplica disponível",
                GuardianPortuguese.errorReason("SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE"));
        assertEquals("Exceção de ponteiro nulo (NullPointerException)",
                GuardianPortuguese.errorReason("NullPointerException"));
        assertEquals("Defeito de código: DIV BY ZERO",
                GuardianPortuguese.errorReason("CODE_DEFECT_DIV_BY_ZERO"));
    }

    @Test
    void translatesK8sConclusions() {
        assertEquals("Falha de nó", GuardianPortuguese.k8sConclusion(K8sConclusion.NODE_FAILURE));
        assertEquals("Infraestrutura transitória, recuperável",
                GuardianPortuguese.k8sConclusion("TRANSIENT_INFRA_RECOVERABLE"));
        assertEquals("sem conclusão K8s", GuardianPortuguese.k8sConclusion((String) null));
    }

    @Test
    void translatesStormAndWaitingReasons() {
        assertEquals("Nó não pronto", GuardianPortuguese.stormReason("NODE_NOT_READY"));
        assertEquals("Container reiniciando em loop", GuardianPortuguese.waitingReason("CrashLoopBackOff"));
        assertEquals("nenhum", GuardianPortuguese.waitingReason(""));
    }

    @Test
    void translatesSeverity() {
        assertEquals("Média", GuardianPortuguese.severity(IncidentSeverity.MEDIUM));
        assertEquals("Crítica", GuardianPortuguese.severity(IncidentSeverity.CRITICAL));
        assertEquals("não informada", GuardianPortuguese.severity(null));
    }

    @Test
    void keepsAlreadyPortugueseText() {
        assertEquals("hotfix deste PR", GuardianPortuguese.errorReason("hotfix deste PR"));
    }

    @Test
    void narrativeRuleRequiresPortuguese() {
        assertTrue(GuardianPortuguese.NARRATIVE_LANGUAGE_RULE.contains("português brasileiro"));
        assertFalse(GuardianPortuguese.NARRATIVE_LANGUAGE_RULE.contains("English"));
    }
}
