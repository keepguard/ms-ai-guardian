package com.keepguard.ms_ai_guardian.application.service.sre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmInvestigationService {

    private final Optional<ChatClient.Builder> chatClientBuilder;
    private final GuardianLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public LlmInvestigationResult investigate(ClusterFacts facts, String errorReason) {
        LlmInvestigationResult heuristic = heuristic(facts, errorReason);
        if (chatClientBuilder.isEmpty() || !llmProperties.isEnabled()) {
            heuristic.setHeuristicFallback(true);
            return heuristic;
        }
        try {
            String prompt = buildPrompt(facts, errorReason);
            String sentinel = "__LLM_TIMEOUT_OR_EMPTY__";
            String raw = LlmContextLimiter.callWithTimeout(
                    () -> chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content(),
                    llmProperties.getTimeoutSeconds(),
                    sentinel);
            if (raw == null || raw.isBlank() || sentinel.equals(raw)) {
                heuristic.setHeuristicFallback(true);
                return heuristic;
            }
            LlmInvestigationResult parsed = parse(raw, heuristic);
            parsed.setHeuristicFallback(false);
            return parsed;
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
                case CONTROLLER_ALREADY_RETRYING -> recommended.add("DISMISS");
                case REPLICAS_INTENTIONALLY_ZERO -> recommended.add("DISMISS");
                case IMAGE_OR_CONFIG, UNSCHEDULABLE, NO_CONTROLLER -> recommended.add("DISMISS");
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

    private String buildPrompt(ClusterFacts facts, String errorReason) {
        return """
                Você é um SRE. Analise SOMENTE os fatos Kubernetes abaixo.
                Não invente comandos. recommendedActionIds deve ser subconjunto de:
                RECREATE_POD, ROLLOUT_RESTART, ROLLBACK_REVISION, SCALE_REPLAY, DISMISS.

                errorReason: %s
                fatos:
                namespace=%s service=%s pod=%s deployment=%s
                desired=%s available=%s ready=%s replicaSets=%s
                phase=%s waiting=%s terminated=%s restarts=%s
                crashLoop=%s imagePull=%s replicasZero=%s conclusion=%s
                events: %s
                logs (truncados): %s

                Responda APENAS JSON:
                {"rootCause":"...","summary":"...","recommendedActionIds":["DISMISS"],"riskNotes":"..."}
                """.formatted(
                errorReason,
                facts.getNamespace(), facts.getServiceName(), facts.getPodName(), facts.getDeploymentName(),
                facts.getDesiredReplicas(), facts.getAvailableReplicas(), facts.getReadyReplicas(),
                facts.getReplicaSetCount(),
                facts.getPhase(), facts.getWaitingReason(), facts.getTerminatedReason(), facts.getRestartCount(),
                facts.isCrashLoop(), facts.isImagePullFailure(), facts.isReplicasIntentionallyZero(),
                facts.getConclusion(),
                facts.getWarningEvents(),
                LlmContextLimiter.tail(facts.getLogsSnippet(), 1200));
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
}
