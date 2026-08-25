package com.keepguard.ms_ai_guardian.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.application.service.agents.CoderAgentService;
import com.keepguard.ms_ai_guardian.application.service.agents.DeployerAgentService;
import com.keepguard.ms_ai_guardian.application.service.agents.ReviewerAgentService;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/guardian/webhooks")
@RequiredArgsConstructor
@Tag(name = "GitHub Webhooks", description = "Receptor de eventos do GitHub para automação Multi-Agent")
public class GitHubWebhookController {

    private final ObjectMapper objectMapper;
    private final CoderAgentService coderAgent;
    private final ReviewerAgentService reviewerAgent;
    private final DeployerAgentService deployerAgent;
    private final PullRequestLifecycleRepository prRepository;

    @PostMapping("/github")
    @Operation(summary = "Receptor de Webhooks do GitHub (Pull Requests, Reviews e Comentários)")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "ping") String githubEvent,
            @RequestBody String rawPayload) {

        log.info("🔔 [GitHub Webhook] Evento recebido: {}", githubEvent);

        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String repoName = payload.path("repository").path("name").asText();

            switch (githubEvent) {
                case "pull_request":
                    handlePullRequestEvent(repoName, payload);
                    break;

                case "pull_request_review_comment":
                case "issue_comment":
                    handleCommentEvent(repoName, payload);
                    break;

                default:
                    log.debug("Evento '{}' ignorado pelo AI Guardian.", githubEvent);
                    break;
            }

            return ResponseEntity.ok("Evento processado pelo KeepGuard Multi-Agent Guardian.");
        } catch (Exception e) {
            log.error("Erro ao processar GitHub Webhook: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("Erro ao processar payload: " + e.getMessage());
        }
    }

    private void handlePullRequestEvent(String repoName, JsonNode payload) {
        String action = payload.path("action").asText();
        int prNumber = payload.path("number").asInt();
        boolean merged = payload.path("pull_request").path("merged").asBoolean(false);
        String sender = payload.path("sender").path("login").asText();

        log.info("📋 [PR Event] Repo: {}, PR #{}, Action: {}, Merged: {}, Sender: {}", repoName, prNumber, action, merged, sender);

        if ("closed".equalsIgnoreCase(action) && merged) {
            // 🎉 MERGE REALIZADO PELO HUMANO (RAFAEL)!
            log.info("🚀 Quality Gate Humano Aprovado! Merge realizado por @{}. Acionando DeployerAgent...", sender);
            deployerAgent.handleMergedPullRequest(repoName, prNumber, sender);

        } else if ("opened".equalsIgnoreCase(action) || "reopened".equalsIgnoreCase(action)) {
            // Aciona o ReviewerAgent para auditar o PR recém-aberto
            prRepository.findByRepoNameAndPrNumber(repoName, prNumber)
                    .ifPresent(reviewerAgent::performReview);
        }
    }

    private void handleCommentEvent(String repoName, JsonNode payload) {
        String action = payload.path("action").asText();
        if (!"created".equalsIgnoreCase(action)) {
            return;
        }

        String commentBody = payload.path("comment").path("body").asText();
        String commentId = payload.path("comment").path("id").asText();
        String author = payload.path("comment").path("user").path("login").asText();
        int prNumber = payload.has("issue") ? payload.path("issue").path("number").asInt() : payload.path("pull_request").path("number").asInt();

        // Ignora comentários gerados pelos próprios robôs do Guardian para evitar loops infinitos
        if (author.contains("bot") || commentBody.contains("[CoderAgent]") || commentBody.contains("[ReviewerAgent]")) {
            log.debug("Comentário do bot ignorado para evitar loops.");
            return;
        }

        log.info("💬 [Review Feedback] Comentário recebido no PR #{} de @{}: {}", prNumber, author, commentBody);

        // 1. CoderAgent aplica a alteração solicitada
        boolean adjusted = coderAgent.applyReviewFeedbackAndNotify(repoName, prNumber, commentId, commentBody, author);

        // 2. ReviewerAgent re-avalia o PR ajustado
        if (adjusted) {
            prRepository.findByRepoNameAndPrNumber(repoName, prNumber)
                    .ifPresent(reviewerAgent::performReview);
        }
    }
}
