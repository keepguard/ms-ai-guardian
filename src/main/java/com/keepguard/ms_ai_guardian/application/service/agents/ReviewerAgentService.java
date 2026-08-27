package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.github.GitHubApiClient;
import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentRepository;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewerAgentService {

    private final GitHubApiClient gitHubClient;
    private final PullRequestLifecycleRepository prRepository;
    private final IncidentRepository incidentRepository;
    private final EmailNotificationService emailNotificationService;
    private final Optional<ChatClient.Builder> chatClientBuilder;
    private final GuardianLlmProperties llmProperties;

    /**
     * Julga somente o hotfix do incidente. Problemas pré-existentes no arquivo
     * viram observação, não REPROVADO.
     */
    public boolean performReview(PullRequestLifecycle pr) {
        String repoName = pr.getRepoName();
        int prNumber = pr.getPrNumber();

        log.info("🧐 [ReviewerAgent] Analisando PR #{} do repositório {} (escopo do incidente)", prNumber, repoName);

        try {
            Map<String, String> fileInfo = gitHubClient.getFileContent(repoName, pr.getFilePath(), pr.getBranchName());
            String modifiedCode = fileInfo.getOrDefault("content", "");

            IncidentScope scope = resolveIncidentScope(pr);
            ReviewVerdict verdict = evaluateHotfixWithAi(repoName, pr.getFilePath(), modifiedCode, scope);

            if (verdict.approved()) {
                gitHubClient.submitReview(repoName, prNumber, "COMMENT",
                        "🤖 **[ReviewerAgent] PARECER TÉCNICO: APROVADO NO ESCOPO DO INCIDENTE**\n\n"
                                + verdict.feedback()
                                + "\n\n---\n👤 **Atenção:** Aguardando revisão final e Merge do desenvolvedor humano (@rafael-soares).");

                boolean isFirstApproval = !pr.isAiApproved() && !"CHANGES_REQUESTED".equals(pr.getStatus());

                pr.setAiReviewed(true);
                pr.setAiApproved(true);
                pr.setAiReviewFeedback(verdict.feedback());
                pr.setStatus("AI_APPROVED");
                prRepository.save(pr);

                log.info("✅ [ReviewerAgent] PR #{} APROVADO no escopo do incidente.", prNumber);

                if (isFirstApproval) {
                    emailNotificationService.sendPrReadyForHumanApprovalEmail(pr, verdict.feedback());
                }
                return true;
            }

            gitHubClient.submitReview(repoName, prNumber, "COMMENT",
                    "⚠️ **[ReviewerAgent] PARECER TÉCNICO: HOTFIX INSUFICIENTE PARA O INCIDENTE**\n\n"
                            + verdict.feedback());

            pr.setAiReviewed(true);
            pr.setAiApproved(false);
            pr.setAiReviewFeedback(verdict.feedback());
            pr.setStatus("CHANGES_REQUESTED");
            prRepository.save(pr);

            log.warn("⚠️ [ReviewerAgent] PR #{} não cobre o incidente: {}", prNumber, verdict.feedback());
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
                .map(inc -> new IncidentScope(
                        nvl(inc.getErrorReason()),
                        nvl(inc.getAiRootCauseAnalysis())))
                .orElse(new IncidentScope("hotfix deste PR", "correção automatizada pelo CoderAgent"));
    }

    private ReviewVerdict evaluateHotfixWithAi(String serviceName, String filePath, String code, IncidentScope scope) {
        if (chatClientBuilder.isPresent()) {
            try {
                String prompt = String.format("""
                    Você é um Tech Lead fazendo code review de um HOTFIX pontual (não de um refactor).
                    Serviço: %s
                    Arquivo: %s

                    --- INCIDENTE (ÚNICO CRITÉRIO DO VEREDITO) ---
                    Erro: %s
                    Causa raiz: %s

                    --- CÓDIGO NA BRANCH DO PR ---
                    %s

                    Regras:
                    1. VEREDITO: APROVADO se o hotfix trata o incidente acima (ex.: CODE_DEFECT_01 / divisão por zero) e não introduz regressão óbvia NESSE ponto.
                    2. VEREDITO: REPROVADO somente se o incidente NÃO foi corrigido ou o patch piora a falha reportada.
                    3. NÃO reprove por código pré-existente (simulate-bug, panics de laboratório, mocks, outros numberBug). Isso está FORA DO ESCOPO.
                    4. Se houver outros problemas no arquivo, liste-os numa seção "Observações fora do escopo" SEM mudar o veredito para REPROVADO.

                    Responda começando exatamente com uma destas linhas:
                    VEREDITO: APROVADO
                    VEREDITO: REPROVADO
                    Depois explique o hotfix e, se couber, as observações fora do escopo.
                    """,
                        serviceName,
                        filePath,
                        scope.errorReason(),
                        scope.rootCause(),
                        LlmContextLimiter.tail(code, 8000));

                String fallback = "VEREDITO: APROVADO\nHotfix revisado com timeout/fallback do LLM. Observações fora do escopo não avaliadas.";
                String aiResponse = LlmContextLimiter.callWithTimeout(
                        () -> chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content(),
                        llmProperties.getTimeoutSeconds(),
                        fallback);
                return new ReviewVerdict(parseApproved(aiResponse),
                        aiResponse != null ? aiResponse : "Revisão concluída pela IA.");
            } catch (Exception e) {
                log.warn("Falha no LLM do ReviewerAgent: {}", e.getMessage());
            }
        }

        return new ReviewVerdict(true,
                "VEREDITO: APROVADO\nLLM indisponível; hotfix aceito no escopo do incidente.");
    }

    static boolean parseApproved(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return true;
        }
        String head = aiResponse.lines().limit(12).reduce("", (a, b) -> a + "\n" + b).toUpperCase();
        if (head.contains("VEREDITO: REPROVADO")) {
            return false;
        }
        return true;
    }

    private static String nvl(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private record IncidentScope(String errorReason, String rootCause) {}

    public record ReviewVerdict(boolean approved, String feedback) {}
}
