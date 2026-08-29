package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.domain.enums.ActionRisk;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import com.keepguard.ms_ai_guardian.domain.enums.RemediationActionType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ActionCatalogPolicy {

    public record SuggestionDraft(
            RemediationActionType actionType,
            String label,
            ActionRisk risk,
            boolean enabled,
            String disabledReason,
            String aiRationale,
            String payloadJson
    ) {}

    private ActionCatalogPolicy() {}

    public static List<SuggestionDraft> build(ClusterFacts facts, LlmInvestigationResult llm) {
        Set<String> recommended = llm != null && llm.getRecommendedActionIds() != null
                ? Set.copyOf(llm.getRecommendedActionIds())
                : Set.of();
        List<SuggestionDraft> drafts = new ArrayList<>();
        drafts.add(recreatePod(facts, recommended));
        drafts.add(rolloutRestart(facts, recommended));
        drafts.add(rollback(facts, recommended));
        drafts.add(scaleReplay(facts, recommended));
        drafts.add(dismiss(recommended));
        return drafts;
    }

    private static SuggestionDraft recreatePod(ClusterFacts facts, Set<String> recommended) {
        boolean crash = facts.isCrashLoop();
        boolean image = facts.isImagePullFailure();
        boolean zero = facts.isReplicasIntentionallyZero();
        boolean nodeOrTransient = facts.getConclusion() == K8sConclusion.NODE_FAILURE
                || facts.getConclusion() == K8sConclusion.TRANSIENT_INFRA_RECOVERABLE
                || "Unknown".equalsIgnoreCase(facts.getPhase());
        String disabled = null;
        if (crash) {
            disabled = "CrashLoopBackOff: o ReplicaSet já reinicia o container; recriar o pod não corrige o processo.";
        } else if (image) {
            disabled = "Falha de imagem/config; recriar o pod não resolve pull/registry.";
        } else if (zero) {
            disabled = "Deployment com replicas=0 de propósito.";
        } else if (!nodeOrTransient) {
            disabled = "Conclusão K8s (" + safeConclusion(facts) + ") não indica pod órfão ou falha de nó.";
        }
        return draft(RemediationActionType.RECREATE_POD, ActionRisk.LOW, disabled, recommended,
                payload(facts));
    }

    private static SuggestionDraft rolloutRestart(ClusterFacts facts, Set<String> recommended) {
        boolean crash = facts.isCrashLoop();
        boolean image = facts.isImagePullFailure();
        boolean zero = facts.isReplicasIntentionallyZero();
        Integer desired = facts.getDesiredReplicas();
        Integer available = facts.getAvailableReplicas();
        boolean outage = desired != null && desired > 0 && (available == null || available == 0);
        String disabled = null;
        if (crash) {
            disabled = "CrashLoopBackOff: restart cego mascara o defeito.";
        } else if (image) {
            disabled = "ImagePull/config: rollout não puxa uma imagem inválida.";
        } else if (zero) {
            disabled = "replicas=0 intencional; use scale apenas com confirmação destrutiva se for o caso.";
        } else if (!outage) {
            disabled = "Deployment não está com available=0 e desired>0.";
        }
        return draft(RemediationActionType.ROLLOUT_RESTART, ActionRisk.HIGH, disabled, recommended,
                payload(facts));
    }

    private static SuggestionDraft rollback(ClusterFacts facts, Set<String> recommended) {
        Integer rs = facts.getReplicaSetCount();
        boolean hasPrevious = rs != null && rs > 1;
        Integer desired = facts.getDesiredReplicas();
        Integer available = facts.getAvailableReplicas();
        boolean outage = desired != null && desired > 0 && (available == null || available == 0);
        String disabled = null;
        if (!hasPrevious) {
            disabled = "Não há ReplicaSet anterior para rollback.";
        } else if (!outage) {
            disabled = "Sem outage de réplicas; rollback não é a primeira opção.";
        }
        return draft(RemediationActionType.ROLLBACK_REVISION, ActionRisk.HIGH, disabled, recommended,
                payload(facts));
    }

    private static SuggestionDraft scaleReplay(ClusterFacts facts, Set<String> recommended) {
        boolean zero = facts.isReplicasIntentionallyZero();
        boolean crash = facts.isCrashLoop();
        boolean image = facts.isImagePullFailure();
        boolean transientInfra = facts.getConclusion() == K8sConclusion.TRANSIENT_INFRA_RECOVERABLE;
        Integer desired = facts.getDesiredReplicas();
        Integer available = facts.getAvailableReplicas();
        boolean outage = desired != null && desired > 0 && (available == null || available == 0);
        String disabled = null;
        if (zero) {
            disabled = "replicas=0 parece intencional; scale replay desabilitado.";
        } else if (crash || image) {
            disabled = "Sintoma de CrashLoop/imagem: zerar e subir não corrige a causa.";
        } else if (!transientInfra || !outage) {
            disabled = "Scale replay só quando a conclusão é TRANSIENT_INFRA_RECOVERABLE com desired>0.";
        }
        return draft(RemediationActionType.SCALE_REPLAY, ActionRisk.DESTRUCTIVE, disabled, recommended,
                payload(facts));
    }

    private static SuggestionDraft dismiss(Set<String> recommended) {
        return draft(RemediationActionType.DISMISS, ActionRisk.LOW, null, recommended, "{}");
    }

    private static SuggestionDraft draft(RemediationActionType type, ActionRisk risk, String disabled,
            Set<String> recommended, String payloadJson) {
        boolean enabled = disabled == null;
        String rationale = recommended.contains(type.name())
                ? "A IA ranqueou esta opção com base nos fatos do cluster."
                : "Opção do catálogo avaliada pela política de SRE.";
        return new SuggestionDraft(type, type.label(), risk, enabled, disabled, rationale, payloadJson);
    }

    private static String payload(ClusterFacts facts) {
        Integer desired = facts.getDesiredReplicas() != null ? facts.getDesiredReplicas() : 1;
        String pod = facts.getPodName() != null ? facts.getPodName() : "";
        String dep = facts.getDeploymentName() != null ? facts.getDeploymentName() : facts.getServiceName();
        return "{\"podName\":\"" + escape(pod) + "\",\"deploymentName\":\"" + escape(dep)
                + "\",\"desiredReplicas\":" + desired + "}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safeConclusion(ClusterFacts facts) {
        return facts.getConclusion() != null ? facts.getConclusion().name() : "UNKNOWN";
    }

    public static Set<RemediationActionType> catalog() {
        return EnumSet.allOf(RemediationActionType.class);
    }
}
