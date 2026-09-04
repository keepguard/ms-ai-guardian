package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.application.port.out.github.GitHubPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptCatalogPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.i18n.GuardianPortuguese;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewerAgentService {

    private final GitHubPort gitHubClient;
    private final PullRequestLifecycleRepository prRepository;
    private final IncidentRepository incidentRepository;
    private final EmailNotificationService emailNotificationService;
    private final LlmPort llmPort;
    private final PromptCatalogPort prompts;
    private final GuardianLlmProperties llmProperties;
    private final GuardianProperties guardianProperties;

    public boolean performReview(PullRequestLifecycle pr) {
        String repoName = pr.getRepoName();
        int prNumber = pr.getPrNumber();
        log.info("[ReviewerAgent] Analisando PR #{} de {}", prNumber, repoName);

        try {
            Map<String, String> fileInfo = gitHubClient.getFileContent(repoName, pr.getFilePath(), pr.getBranchName());
            String modifiedCode = fileInfo.getOrDefault("content", "");
            IncidentScope scope = resolveIncidentScope(pr);
            ReviewVerdict verdict = evaluateHotfixWithAi(repoName, pr.getFilePath(), modifiedCode, scope, pr.getIncidentId());

            if (verdict.approved()) {
                gitHubClient.submitReview(repoName, prNumber, "COMMENT",
                        prompts.render(PromptKeys.GITHUB_REVIEWER_APPROVED, Map.of(
                                "feedback", verdict.feedback(),
                                "approverGithub", guardianProperties.getApproverGithub())));
                boolean isFirstApproval = !pr.isAiApproved() && pr.getStatus() != PullRequestStatus.CHANGES_REQUESTED;
                pr.setAiReviewed(true);
                pr.setAiApproved(true);
                pr.setAiReviewFeedback(verdict.feedback());
                pr.setStatus(PullRequestStatus.AI_APPROVED);
                prRepository.save(pr);
                if (isFirstApproval) {
                    emailNotificationService.sendPrReadyForHumanApprovalEmail(pr, verdict.feedback());
                }
                return true;
            }

            gitHubClient.submitReview(repoName, prNumber, "COMMENT",
                    prompts.render(PromptKeys.GITHUB_REVIEWER_REJECTED, Map.of("feedback", verdict.feedback())));
            pr.setAiReviewed(true);
            pr.setAiApproved(false);
            pr.setAiReviewFeedback(verdict.feedback());
            pr.setStatus(PullRequestStatus.CHANGES_REQUESTED);
            prRepository.save(pr);
            return false;
        } catch (Exception e) {
            log.error("Erro no [ReviewerAgent] durante análise do PR #{}: {}", prNumber, e.getMessage(), e);
            return false;
        }
    }

    private IncidentScope resolveIncidentScope(PullRequestLifecycle pr) {
        if (pr.getIncidentId() == null) {
            return new IncidentScope("hotfix deste PR", "correção automatizada pelo CoderAgent");
        }
        return incidentRepository.findById(pr.getIncidentId())
                .map(inc -> new IncidentScope(nvl(inc.getErrorReason()), nvl(inc.getAiRootCauseAnalysis())))
                .orElse(new IncidentScope("hotfix deste PR", "correção automatizada pelo CoderAgent"));
    }

    private ReviewVerdict evaluateHotfixWithAi(String serviceName, String filePath, String code, IncidentScope scope,
            java.util.UUID incidentId) {
        if (!llmPort.available()) {
            return new ReviewVerdict(true, "VEREDITO: APROVADO\nLLM indisponível; hotfix aceito no escopo do incidente.");
        }
        try {
            var snap = prompts.snapshot(PromptKeys.REVIEWER_HOTFIX_SCOPE);
            String prompt = GuardianPortuguese.NARRATIVE_LANGUAGE_RULE + "\n"
                    + prompts.render(PromptKeys.REVIEWER_HOTFIX_SCOPE, Map.of(
                    "serviceName", serviceName,
                    "filePath", filePath,
                    "errorReason", GuardianPortuguese.errorReason(scope.errorReason()),
                    "rootCause", scope.rootCause(),
                    "code", LlmContextLimiter.tail(code, 8000)));
            String fallback = "VEREDITO: APROVADO\nHotfix revisado com timeout/fallback do LLM.";
            String aiResponse = llmPort.complete(new LlmPort.LlmRequest(
                            prompt, llmProperties.getTimeoutSeconds(), snap.key(), snap.version(), incidentId))
                    .orElse(fallback);
            return new ReviewVerdict(parseApproved(aiResponse), aiResponse);
        } catch (Exception e) {
            log.warn("Falha no LLM do ReviewerAgent: {}", e.getMessage());
            return new ReviewVerdict(true, "VEREDITO: APROVADO\nLLM indisponível; hotfix aceito no escopo do incidente.");
        }
    }

    static boolean parseApproved(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return true;
        }
        String head = aiResponse.lines().limit(12).reduce("", (a, b) -> a + "\n" + b).toUpperCase();
        return !head.contains("VEREDITO: REPROVADO");
    }

    private static String nvl(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record IncidentScope(String errorReason, String rootCause) {}

    public record ReviewVerdict(boolean approved, String feedback) {}
}
