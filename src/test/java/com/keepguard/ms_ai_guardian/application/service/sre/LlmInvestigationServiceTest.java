package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmInvestigationServiceTest {

    @Test
    void heuristicSummaryAndRootCauseArePortuguese() {
        ClusterFacts facts = ClusterFacts.builder()
                .serviceName("srv-email-sender")
                .desiredReplicas(1)
                .availableReplicas(0)
                .waitingReason("NodeNotReady")
                .conclusion(K8sConclusion.NODE_FAILURE)
                .healthy(false)
                .build();

        LlmInvestigationResult result = LlmInvestigationService.heuristic(
                facts, "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE");

        assertTrue(result.getRootCause().contains("nenhuma réplica disponível"));
        assertTrue(result.getRootCause().contains("Falha de nó"));
        assertFalse(result.getRootCause().contains("SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE"));
        assertTrue(result.getSummary().contains("Réplicas desejadas"));
        assertTrue(result.getSummary().contains("motivo de espera"));
        assertTrue(result.getSummary().contains("Nó não pronto"));
        assertFalse(result.getSummary().contains("desired="));
        assertTrue(result.getRiskNotes().contains("mesa SRE"));
    }
}
