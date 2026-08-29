package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import com.keepguard.ms_ai_guardian.domain.enums.RemediationActionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionCatalogPolicyTest {

    @Test
    void crashLoopDisablesRestartAndRecreate() {
        ClusterFacts facts = ClusterFacts.builder()
                .conclusion(K8sConclusion.CONTROLLER_ALREADY_RETRYING)
                .crashLoop(true)
                .desiredReplicas(1)
                .availableReplicas(0)
                .replicaSetCount(2)
                .build();
        var drafts = ActionCatalogPolicy.build(facts, LlmInvestigationResult.builder().build());
        assertFalse(enabled(drafts, RemediationActionType.RECREATE_POD));
        assertFalse(enabled(drafts, RemediationActionType.ROLLOUT_RESTART));
        assertFalse(enabled(drafts, RemediationActionType.SCALE_REPLAY));
        assertTrue(enabled(drafts, RemediationActionType.DISMISS));
    }

    @Test
    void transientOutageEnablesRestart() {
        ClusterFacts facts = ClusterFacts.builder()
                .conclusion(K8sConclusion.TRANSIENT_INFRA_RECOVERABLE)
                .desiredReplicas(2)
                .availableReplicas(0)
                .replicaSetCount(2)
                .podName("ms-auth-abc")
                .deploymentName("ms-auth")
                .serviceName("ms-auth")
                .build();
        var drafts = ActionCatalogPolicy.build(facts, LlmInvestigationResult.builder()
                .recommendedActionIds(List.of("ROLLOUT_RESTART"))
                .build());
        assertTrue(enabled(drafts, RemediationActionType.ROLLOUT_RESTART));
        assertTrue(enabled(drafts, RemediationActionType.RECREATE_POD));
        assertTrue(enabled(drafts, RemediationActionType.SCALE_REPLAY));
        assertTrue(enabled(drafts, RemediationActionType.ROLLBACK_REVISION));
    }

    @Test
    void intentionalZeroDisablesScaleReplay() {
        ClusterFacts facts = ClusterFacts.builder()
                .conclusion(K8sConclusion.REPLICAS_INTENTIONALLY_ZERO)
                .replicasIntentionallyZero(true)
                .desiredReplicas(0)
                .availableReplicas(0)
                .build();
        var drafts = ActionCatalogPolicy.build(facts, LlmInvestigationResult.builder().build());
        assertFalse(enabled(drafts, RemediationActionType.SCALE_REPLAY));
        assertFalse(enabled(drafts, RemediationActionType.ROLLOUT_RESTART));
        assertTrue(enabled(drafts, RemediationActionType.DISMISS));
    }

    private static boolean enabled(List<ActionCatalogPolicy.SuggestionDraft> drafts, RemediationActionType type) {
        return drafts.stream().filter(d -> d.actionType() == type).findFirst().orElseThrow().enabled();
    }
}
