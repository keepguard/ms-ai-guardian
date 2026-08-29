package com.keepguard.ms_ai_guardian.application.service.sre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptCatalogPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmInvestigationService {

    private final LlmPort llmPort;
    private final PromptCatalogPort prompts;
    private final GuardianLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public LlmInvestigationResult investigate(ClusterFacts facts, String errorReason) {
        LlmInvestigationResult heuristic = heuristic(facts, errorReason);
        if (!llmPort.available()) {
            heuristic.setHeuristicFallback(true);
            return heuristic;
        }
        try {
            var snap = prompts.snapshot(PromptKeys.SRE_INVESTIGATE);
            Map<String, String> vars = new HashMap<>();
            vars.put("errorReason", nvl(errorReason));
            vars.put("namespace", nvl(facts.getNamespace()));
            vars.put("serviceName", nvl(facts.getServiceName()));
            vars.put("podName", nvl(facts.getPodName()));
            vars.put("deploymentName", nvl(facts.getDeploymentName()));
            vars.put("desiredReplicas", String.valueOf(facts.getDesiredReplicas()));
            vars.put("availableReplicas", String.valueOf(facts.getAvailableReplicas()));
            vars.put("readyReplicas", String.valueOf(facts.getReadyReplicas()));
            vars.put("replicaSetCount", String.valueOf(facts.getReplicaSetCount()));
            vars.put("phase", nvl(facts.getPhase()));
            vars.put("waitingReason", nvl(facts.getWaitingReason()));
            vars.put("terminatedReason", nvl(facts.getTerminatedReason()));
            vars.put("restartCount", String.valueOf(facts.getRestartCount()));
            vars.put("crashLoop", String.valueOf(facts.isCrashLoop()));
            vars.put("imagePullFailure", String.valueOf(facts.isImagePullFailure()));
            vars.put("replicasIntentionallyZero", String.valueOf(facts.isReplicasIntentionallyZero()));
            vars.put("conclusion", facts.getConclusion() != null ? facts.getConclusion().name() : "UNKNOWN");
            vars.put("warningEvents", String.valueOf(facts.getWarningEvents()));
            vars.put("logsSnippet", LlmContextLimiter.tail(facts.getLogsSnippet(), 1200));
            String prompt = prompts.render(PromptKeys.SRE_INVESTIGATE, vars);
            return llmPort.complete(new LlmPort.LlmRequest(
                            prompt, llmProperties.getTimeoutSeconds(), snap.key(), snap.version(), null))
                    .map(raw -> {
                        LlmInvestigationResult parsed = parse(raw, heuristic);
                        parsed.setHeuristicFallback(false);
                        return parsed;
                    })
                    .orElseGet(() -> {
                        heuristic.setHeuristicFallback(true);
                        return heuristic;
                    });
        } catch (Exception e) {
            log.warn("LLM de investigação falhou, usando heurística: {}", e.getMessage());
            heuristic.setHeuristicFallback(true);
            return heuristic;
        }
    }

    static LlmInvestigationResult heuristic(ClusterFacts facts, String errorReason) {
        String conclusion = facts.getConclusion() != null ? facts.getConclusion().name() : "UNKNOWN";
        String root = (errorReason != null ? errorReason : "anomalia") + " — conclusão K8s: " + conclusion;
        String summary = "O controlador " + (facts.isHealthy() ? "parece saudável" : "não restabeleceu o serviço")
                + ". desired=" + facts.getDesiredReplicas()
                + " available=" + facts.getAvailableReplicas()
                + " waiting=" + facts.getWaitingReason();
        List<String> recommended = new ArrayList<>();
        if (facts.getConclusion() != null) {
            switch (facts.getConclusion()) {
                case NODE_FAILURE, TRANSIENT_INFRA_RECOVERABLE -> recommended.add("RECREATE_POD");
                case CONTROLLER_ALREADY_RETRYING, REPLICAS_INTENTIONALLY_ZERO, IMAGE_OR_CONFIG, UNSCHEDULABLE,
                        NO_CONTROLLER -> recommended.add("DISMISS");
            }
        }
        if (facts.getDesiredReplicas() != null && facts.getDesiredReplicas() > 0
                && (facts.getAvailableReplicas() == null || facts.getAvailableReplicas() == 0)
                && !facts.isCrashLoop() && !facts.isImagePullFailure()) {
            recommended.add("ROLLOUT_RESTART");
        }
        return LlmInvestigationResult.builder()
                .rootCause(root)
                .summary(summary)
                .riskNotes("Ações mutativas só após confirmação humana na mesa SRE.")
                .recommendedActionIds(recommended)
                .heuristicFallback(true)
                .build();
    }

    private LlmInvestigationResult parse(String raw, LlmInvestigationResult fallback) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            List<String> ids = new ArrayList<>();
            if (node.has("recommendedActionIds") && node.get("recommendedActionIds").isArray()) {
                node.get("recommendedActionIds").forEach(n -> ids.add(n.asText()));
            }
            return LlmInvestigationResult.builder()
                    .rootCause(text(node, "rootCause", fallback.getRootCause()))
                    .summary(text(node, "summary", fallback.getSummary()))
                    .riskNotes(text(node, "riskNotes", fallback.getRiskNotes()))
                    .recommendedActionIds(ids.isEmpty() ? fallback.getRecommendedActionIds() : ids)
                    .build();
        } catch (Exception e) {
            log.warn("JSON da IA inválido: {}", e.getMessage());
            fallback.setHeuristicFallback(true);
            return fallback;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).asText("").isBlank() ? node.get(field).asText() : fallback;
    }

    private static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
