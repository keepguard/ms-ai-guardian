package com.keepguard.ms_ai_guardian.adapters.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.application.service.pr.HandlePrEventUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/guardian/webhooks")
@RequiredArgsConstructor
@Tag(name = "GitHub Webhooks", description = "Receptor de eventos do GitHub para automação Multi-Agent")
public class GitHubWebhookController {

    private final ObjectMapper objectMapper;
    private final HandlePrEventUseCase handlePrEvent;

    @PostMapping("/github")
    @Operation(summary = "Receptor de Webhooks do GitHub (Pull Requests, Reviews e Comentários)")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "ping") String githubEvent,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String rawPayload) {

        log.info("[GitHub Webhook] Evento recebido: {}", githubEvent);
        if (!handlePrEvent.beginDelivery(deliveryId)) {
            return ResponseEntity.ok("Entrega duplicada ignorada.");
        }

        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String repoName = payload.path("repository").path("name").asText();

            switch (githubEvent) {
                case "pull_request" -> {
                    String action = payload.path("action").asText();
                    int prNumber = payload.path("number").asInt();
                    boolean merged = payload.path("pull_request").path("merged").asBoolean(false);
                    String sender = payload.path("sender").path("login").asText();
                    handlePrEvent.onPullRequest(repoName, prNumber, action, merged, sender);
                }
                case "pull_request_review_comment", "issue_comment" -> {
                    if (!"created".equalsIgnoreCase(payload.path("action").asText())) {
                        break;
                    }
                    String commentBody = payload.path("comment").path("body").asText();
                    String commentId = payload.path("comment").path("id").asText();
                    String author = payload.path("comment").path("user").path("login").asText();
                    int prNumber = payload.has("issue")
                            ? payload.path("issue").path("number").asInt()
                            : payload.path("pull_request").path("number").asInt();
                    handlePrEvent.onComment(repoName, prNumber, commentId, commentBody, author);
                }
                default -> log.debug("Evento '{}' ignorado pelo AI Guardian.", githubEvent);
            }
            return ResponseEntity.ok("Evento processado pelo KeepGuard Multi-Agent Guardian.");
        } catch (Exception e) {
            log.error("Erro ao processar GitHub Webhook: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("Erro ao processar payload: " + e.getMessage());
        }
    }
}
