package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.application.port.out.github.GitHubPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptCatalogPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.domain.classification.BusinessVerdict;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.i18n.GuardianPortuguese;
import com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter;
import com.keepguard.ms_ai_guardian.infrastructure.util.ScopedSourcePatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoderAgentService {

    private final GitHubPort gitHubClient;
    private final PullRequestLifecycleRepository prRepository;
    private final EmailNotificationService emailNotificationService;
    private final SourceFileResolver sourceFileResolver;
    private final SoftwareArchitectAgentService architectAgentService;
    private final QaAutomationAgentService qaAutomationAgentService;
    private final LlmPort llmPort;
    private final PromptCatalogPort prompts;
    private final GuardianLlmProperties llmProperties;
    private final GuardianProperties guardianProperties;

    public Optional<PullRequestLifecycle> createHotfixPullRequest(
            DiagnosticResultDTO incident,
            String rawStackTrace,
            BusinessVerdict businessVerdict) {

        String repoName = incident.getServiceName();
        String baseBranch = "main";
        var activePrs = prRepository.findByRepoNameAndIncidentIdAndStatusIn(
                repoName, incident.getIncidentId(), PullRequestStatus.active());

        for (var pr : activePrs) {
            var ghStatus = gitHubClient.getPullRequestStatus(repoName, pr.getPrNumber());
            String state = (String) ghStatus.getOrDefault("state", "open");
            if ("open".equalsIgnoreCase(state)) {
                log.info("Já existe um Pull Request ativo para o incidente {} (PR #{}).",
                        incident.getIncidentId(), pr.getPrNumber());
                return Optional.of(pr);
            }
            pr.setStatus(PullRequestStatus.CLOSED);
            savePrState(pr);
        }

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String newBranchName = "fix/guardian-" + repoName + "-" + shortId;
        log.info("[CoderAgent] Criando hotfix PR para {}", repoName);

        try {
            String baseSha = gitHubClient.getBranchSha(repoName, baseBranch);
            if (!gitHubClient.createBranch(repoName, newBranchName, baseSha)) {
                return Optional.empty();
            }

            var resolved = sourceFileResolver.resolve(repoName, baseBranch, rawStackTrace, incident.getErrorReason());
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            String targetPath = resolved.get().path();
            String currentCode = resolved.get().content();
            String fileSha = resolved.get().sha();
            Integer incidentLine = resolved.get().lineNumber();

            String fixedCode = generateCodeFixWithAi(repoName, targetPath, incident, currentCode, rawStackTrace,
                    incidentLine);
            if (fixedCode.equals(currentCode)) {
                log.warn("[CoderAgent] Patch idêntico à main — PR não será aberto.");
                return Optional.empty();
            }

            var qaReport = qaAutomationAgentService.certifyQuality(repoName, targetPath, currentCode, fixedCode,
                    joinIncidentText(incident));
            var archAssessment = architectAgentService.designSolution(incident, repoName, targetPath, rawStackTrace);

            String commitMsg = "fix(" + repoName + "): correção automatizada por KeepGuard AI Guardian\n\nCausa: "
                    + incident.getRootCause();
            if (!gitHubClient.commitFileChange(repoName, targetPath, fixedCode, commitMsg, newBranchName, fileSha)) {
                return Optional.empty();
            }

            String prTitle = "🚨 [AI Guardian Hotfix] Correção de Incidente: " + incident.getErrorReason();
            String prBody = buildPrDescriptionMarkdown(incident, targetPath, incident.getRootCause(),
                    incident.getRecommendedAction(), businessVerdict, archAssessment, qaReport);
            Map<String, Object> prResult = gitHubClient.createPullRequest(repoName, prTitle, prBody, newBranchName,
                    baseBranch);

            int prNumber = (int) prResult.get("prNumber");
            String prUrl = (String) prResult.get("htmlUrl");

            PullRequestLifecycle lifecycle = PullRequestLifecycle.builder()
                    .incidentId(incident.getIncidentId())
                    .repoName(repoName)
                    .branchName(newBranchName)
                    .baseBranch(baseBranch)
                    .filePath(targetPath)
                    .prNumber(prNumber)
                    .prUrl(prUrl)
                    .status(PullRequestStatus.OPEN)
                    .aiReviewed(false)
                    .aiApproved(false)
                    .humanApproved(false)
                    .mergedByHuman(false)
                    .deployedToK8s(false)
                    .build();

            savePrState(lifecycle);
            try {
                emailNotificationService.sendPrOpenedEmail(lifecycle, incident);
            } catch (Exception mailEx) {
                log.warn("PR #{} aberto, mas o e-mail falhou: {}", prNumber, mailEx.getMessage());
            }
            return Optional.of(lifecycle);
        } catch (Exception e) {
            log.error("Erro no [CoderAgent] ao criar hotfix PR para {}: {}", repoName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public boolean applyReviewFeedbackAndNotify(String repoName, int prNumber, String commentId, String commentFeedback,
            String author) {
        Optional<PullRequestLifecycle> prOpt = prRepository.findByRepoNameAndPrNumber(repoName, prNumber);
        if (prOpt.isEmpty()) {
            return false;
        }
        PullRequestLifecycle lifecycle = prOpt.get();
        Map<String, String> currentFile = gitHubClient.getFileContent(repoName, lifecycle.getFilePath(),
                lifecycle.getBranchName());
        String currentContent = currentFile.getOrDefault("content", "");
        String fileSha = currentFile.get("sha");
        String adjustedCode = generateIterativeAdjustmentWithAi(currentContent, commentFeedback);

        if (adjustedCode.equals(currentContent)) {
            String technicalReply = prompts.render(PromptKeys.GITHUB_CODER_NO_CHANGE,
                    Map.of("commentFeedback", commentFeedback));
            gitHubClient.replyToPrReviewComment(repoName, prNumber, commentId, technicalReply);
            emailNotificationService.sendCommentRepliedEmail(lifecycle, author, commentFeedback, technicalReply, false);
            return false;
        }

        String commitMsg = "fix(review): ajuste solicitado por @" + author + "\n\n" + commentFeedback;
        boolean committed = gitHubClient.commitFileChange(repoName, lifecycle.getFilePath(), adjustedCode, commitMsg,
                lifecycle.getBranchName(), fileSha);
        if (!committed) {
            return false;
        }
        lifecycle.setStatus(PullRequestStatus.CHANGES_REQUESTED);
        lifecycle.setAiReviewed(false);
        lifecycle.setAiApproved(false);
        savePrState(lifecycle);

        String reply = prompts.render(PromptKeys.GITHUB_CODER_CHANGE_APPLIED, Map.of(
                "commentFeedback", commentFeedback,
                "branch", lifecycle.getBranchName(),
                "approverGithub", guardianProperties.getApproverGithub()));
        gitHubClient.replyToPrReviewComment(repoName, prNumber, commentId, reply);
        emailNotificationService.sendCommentRepliedEmail(lifecycle, author, commentFeedback, reply, true);
        return true;
    }

    @Transactional
    public PullRequestLifecycle savePrState(PullRequestLifecycle pr) {
        return prRepository.save(pr);
    }

    private String generateCodeFixWithAi(String serviceName, String filePath, DiagnosticResultDTO incident,
            String currentCode, String stackTrace, Integer incidentLine) {
        if (!llmPort.available()) {
            log.warn("[CoderAgent] LLM indisponível.");
            return currentCode;
        }
        try {
            var slice = ScopedSourcePatcher.extract(
                    currentCode, filePath, incident.getErrorReason(), incident.getRootCause(), incidentLine);
            String language = switch (ScopedSourcePatcher.languageOf(filePath)) {
                case "go" -> "Golang";
                case "java" -> "Java";
                default -> "a linguagem do arquivo";
            };
            String scopeHint = slice.isWholeFile()
                    ? "o menor trecho possível do arquivo"
                    : "SOMENTE a função/método extraído abaixo (não a classe/arquivo inteiro)";
            var snap = prompts.snapshot(PromptKeys.CODER_HOTFIX);
            Map<String, String> vars = new HashMap<>();
            vars.put("language", language);
            vars.put("serviceName", serviceName);
            vars.put("filePath", filePath);
            vars.put("errorReason", nvl(incident.getErrorReason()));
            vars.put("rootCause", nvl(incident.getRootCause()));
            vars.put("incidentLine", incidentLine != null ? incidentLine.toString() : "desconhecida");
            vars.put("scopeHint", scopeHint);
            vars.put("functionSource", slice.functionSource());
            vars.put("stackTrace", LlmContextLimiter.tail(stackTrace, 1200));
            String prompt = prompts.render(PromptKeys.CODER_HOTFIX, vars);
            Optional<String> aiResult = llmPort.complete(new LlmPort.LlmRequest(
                    prompt, llmProperties.getCodegenTimeoutSeconds(), snap.key(), snap.version(),
                    incident.getIncidentId()));
            if (aiResult.isEmpty()) {
                return currentCode;
            }
            String patched = ScopedSourcePatcher.applyReplacement(slice, aiResult.get());
            return patched.equals(currentCode) ? currentCode : patched;
        } catch (Exception e) {
            log.warn("Falha no LLM ao gerar código: {}.", e.getMessage());
            return currentCode;
        }
    }

    private String generateIterativeAdjustmentWithAi(String currentCode, String feedback) {
        if (llmPort.available()) {
            try {
                var snap = prompts.snapshot(PromptKeys.CODER_REVIEW_ADJUST);
                String prompt = prompts.render(PromptKeys.CODER_REVIEW_ADJUST, Map.of(
                        "feedback", nvl(feedback),
                        "currentCode", currentCode));
                Optional<String> aiResult = llmPort.complete(new LlmPort.LlmRequest(
                        prompt, llmProperties.getCodegenTimeoutSeconds(), snap.key(), snap.version(), null));
                if (aiResult.isPresent() && !aiResult.get().equals(currentCode)) {
                    return aiResult.get().replaceAll("```[a-z]*\n?", "").replaceAll("```", "").trim();
                }
            } catch (Exception e) {
                log.warn("Falha no LLM de ajuste iterativo: {}", e.getMessage());
            }
        }
        return applyHeuristicReviewDirectives(currentCode, feedback);
    }

    static String applyHeuristicReviewDirectives(String currentCode, String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return currentCode;
        }
        String fb = feedback.toLowerCase();
        if ((fb.contains("coment") || fb.contains("comentário"))
                && (fb.contains("remov") || fb.contains("tirar") || fb.contains("apagar") || fb.contains("deletar")
                        || fb.contains("sem") || fb.contains("limp") || fb.contains("nao") || fb.contains("não"))) {
            String cleaned = currentCode.replaceAll("(?m)^[ \\t]*//.*\\R?", "")
                    .replaceAll("(?m)[ \\t]+//.*$", "")
                    .replaceAll("/\\*(?s:.*?)\\*/", "");
            if (!cleaned.equals(currentCode)) {
                return cleaned.trim() + "\n";
            }
        }
        if (fb.contains("linha") && (fb.contains("remov") || fb.contains("apagar") || fb.contains("deletar"))) {
            String cleaned = currentCode.replaceAll("(?m)^[ \\t]*//.*\\R?", "");
            if (!cleaned.equals(currentCode)) {
                return cleaned.trim() + "\n";
            }
        }
        return currentCode;
    }

    private String buildPrDescriptionMarkdown(
            DiagnosticResultDTO incident,
            String filePath,
            String rootCause,
            String action,
            BusinessVerdict businessVerdict,
            SoftwareArchitectAgentService.ArchitecturalAssessment archAssessment,
            QaAutomationAgentService.QaCertificationReport qaReport) {

        String businessSection = businessVerdict != null
                ? """
                ### 👔 Análise de Negócio & Dados (BusinessAnalystAgent)
                - **Diagnóstico Funcional:** %s
                - **Impacto no Domínio:** %s
                """.formatted(businessVerdict.summary(), businessVerdict.businessContext())
                : "";
        String archSection = archAssessment != null
                ? """
                ### 📐 Análise de Arquitetura & Sequência (SoftwareArchitectAgent)
                - **Padrão Arquitetural:** `%s`
                %s

                #### 🔴 Fluxo Atual com Falha (Antes)
                %s

                #### 🟢 Fluxo Proposto Corrigido (Depois)
                %s
                """.formatted(archAssessment.pattern(), archAssessment.summary(),
                        archAssessment.currentFlowMermaid(), archAssessment.proposedFlowMermaid())
                : "";
        String qaSection = qaReport != null
                ? """
                ### 🧪 Certificação de Qualidade (QaAutomationAgent)
                **Status Geral:** `%s`

                %s
                """.formatted(qaReport.verdictText(), qaReport.toMarkdownTable())
                : "";

        Map<String, String> vars = new HashMap<>();
        vars.put("serviceName", nvl(incident.getServiceName()));
        vars.put("podName", nvl(incident.getPodName()));
        vars.put("severity", GuardianPortuguese.severity(incident.getSeverity()));
        vars.put("errorReason", GuardianPortuguese.errorReason(incident.getErrorReason()));
        vars.put("filePath", nvl(filePath));
        vars.put("rootCause", nvl(rootCause));
        vars.put("action", nvl(action));
        vars.put("businessSection", businessSection);
        vars.put("archSection", archSection);
        vars.put("qaSection", qaSection);
        vars.put("approverGithub", guardianProperties.getApproverGithub());
        return prompts.render(PromptKeys.PR_BODY, vars);
    }

    private static String joinIncidentText(DiagnosticResultDTO incident) {
        if (incident == null) {
            return "";
        }
        return nvl(incident.getErrorReason()) + " " + nvl(incident.getRootCause());
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
