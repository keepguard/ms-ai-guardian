package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.github.GitHubApiClient;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoderAgentService {

    private final GitHubApiClient gitHubClient;
    private final PullRequestLifecycleRepository prRepository;
    private final com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService emailNotificationService;
    private final SoftwareArchitectAgentService architectAgentService;
    private final QaAutomationAgentService qaAutomationAgentService;
    private final Optional<ChatClient.Builder> chatClientBuilder;
    private final GuardianLlmProperties llmProperties;

    /**
     * Cria uma branch, aplica a correção gerada pela IA e abre um Pull Request
     * enriquecido com Arquitetura e QA.
     */
    public Optional<PullRequestLifecycle> createHotfixPullRequest(
            DiagnosticResultDTO incident,
            String filePath,
            String rawStackTrace,
            BusinessAnalystAgentService.BusinessVerdict businessVerdict) {

        String repoName = incident.getServiceName(); // ms-auth, ms-user, etc.
        String baseBranch = "main";
        // 0. TRAVA DE IDEMPOTÊNCIA ANTI-DUPLICAÇÃO DE PRS POR ERRO/INCIDENTE
        List<PullRequestLifecycle> activePrs = prRepository.findAll().stream()
                .filter(pr -> repoName.equalsIgnoreCase(pr.getRepoName()) &&
                        incident.getIncidentId().equals(pr.getIncidentId()) &&
                        ("OPEN".equals(pr.getStatus()) || "AI_APPROVED".equals(pr.getStatus())
                                || "CHANGES_REQUESTED".equals(pr.getStatus())))
                .toList();

        for (var pr : activePrs) {
            var ghStatus = gitHubClient.getPullRequestStatus(repoName, pr.getPrNumber());
            String state = (String) ghStatus.getOrDefault("state", "open");
            if ("open".equalsIgnoreCase(state)) {
                log.info("🛡️ [CoderAgent] Já existe um Pull Request ativo para o mesmo incidente {} (PR #{}: {}).",
                        incident.getIncidentId(), pr.getPrNumber(), pr.getPrUrl());
                return Optional.of(pr);
            } else {
                pr.setStatus("CLOSED");
                savePrState(pr);
            }
        }

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String newBranchName = "fix/guardian-" + repoName + "-" + shortId;

        log.info("🛠️ [CoderAgent] Iniciando criação de Pull Request para hotfix do serviço: {}", repoName);

        try {
            // 1. Obtém o SHA da branch principal (main)
            String baseSha = gitHubClient.getBranchSha(repoName, baseBranch);

            // 2. Cria a nova branch
            boolean branchCreated = gitHubClient.createBranch(repoName, newBranchName, baseSha);
            if (!branchCreated) {
                log.error("Falha ao criar branch {} no repo {}", newBranchName, repoName);
                return Optional.empty();
            }

            // 3. Lê o código atual do arquivo no GitHub (se o caminho foi identificado)
            String targetPath = filePath != null && !filePath.isBlank() ? filePath
                    : "src/main/resources/application.yml";
            Map<String, String> fileInfo = gitHubClient.getFileContent(repoName, targetPath, baseBranch);
            String currentCode = fileInfo.getOrDefault("content", "# Conteúdo original indisponível");
            String fileSha = fileInfo.get("sha");

            // 4. Solicita ao modelo de IA a correção precisa do código
            String fixedCode = generateCodeFixWithAi(repoName, targetPath, incident, currentCode, rawStackTrace);

            // VALIDAÇÃO CRÍTICA: Se a correção for idêntica ao código já presente na main,
            // aborta a criação de PR vazio
            if (fixedCode.equals(currentCode)) {
                log.warn(
                        "⚠️ [CoderAgent] O código corrigido é idêntico ao código atual na branch {}. PR não será criado para evitar commits vazios.",
                        baseBranch);
                return Optional.empty();
            }

            // 5. 🧪 QA AUTOMATION AGENT: Certifica e testa o hotfix antes de commitar
            var qaReport = qaAutomationAgentService.certifyQuality(repoName, targetPath, fixedCode,
                    incident.getErrorReason());

            // 6. 📐 SOFTWARE ARCHITECT AGENT: Analisa arquitetura e gera diagramas Mermaid
            // Antes vs Depois
            var archAssessment = architectAgentService.designSolution(incident, repoName, targetPath, rawStackTrace);

            // 7. Commita a correção na branch
            String commitMsg = "fix(" + repoName + "): correção automatizada por KeepGuard AI Guardian\n\nCausa: "
                    + incident.getRootCause();
            boolean committed = gitHubClient.commitFileChange(repoName, targetPath, fixedCode, commitMsg, newBranchName,
                    fileSha);
            if (!committed) {
                log.error("Falha ao commitar código na branch {}", newBranchName);
                return Optional.empty();
            }

            // 8. Abre o Pull Request Rico
            String prTitle = "🚨 [AI Guardian Hotfix] Correção de Incidente: " + incident.getErrorReason();
            String prBody = buildPrDescriptionMarkdown(incident, targetPath, incident.getRootCause(),
                    incident.getRecommendedAction(), businessVerdict, archAssessment, qaReport);
            Map<String, Object> prResult = gitHubClient.createPullRequest(repoName, prTitle, prBody, newBranchName,
                    baseBranch);

            int prNumber = (int) prResult.get("prNumber");
            String prUrl = (String) prResult.get("htmlUrl");

            // 7. Persiste o ciclo de vida do PR no banco de dados
            PullRequestLifecycle lifecycle = PullRequestLifecycle.builder()
                    .incidentId(incident.getIncidentId())
                    .repoName(repoName)
                    .branchName(newBranchName)
                    .baseBranch(baseBranch)
                    .filePath(targetPath)
                    .prNumber(prNumber)
                    .prUrl(prUrl)
                    .status("OPEN")
                    .aiReviewed(false)
                    .aiApproved(false)
                    .humanApproved(false)
                    .mergedByHuman(false)
                    .deployedToK8s(false)
                    .build();

            savePrState(lifecycle);
            log.info("✅ [CoderAgent] Pull Request #{} aberto com sucesso: {}", prNumber, prUrl);
            try {
                emailNotificationService.sendPrOpenedEmail(lifecycle, incident);
            } catch (Exception mailEx) {
                log.warn("PR #{} aberto, mas o e-mail de notificação falhou: {}", prNumber, mailEx.getMessage());
            }
            return Optional.of(lifecycle);

        } catch (Exception e) {
            log.error("Erro no [CoderAgent] ao criar hotfix PR para {}: {}", repoName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Ajusta o código do PR com base no comentário/feedback recebido da revisão.
     */
    public boolean applyReviewFeedbackAndNotify(String repoName, int prNumber, String commentId, String commentFeedback,
            String author) {
        log.info("🛠️ [CoderAgent] Ajustando código do PR #{} com base no feedback de {}: {}", prNumber, author,
                commentFeedback);

        Optional<PullRequestLifecycle> prOpt = prRepository.findByRepoNameAndPrNumber(repoName, prNumber);
        if (prOpt.isEmpty()) {
            log.warn("Ciclo de vida de PR não encontrado para {} #{}", repoName, prNumber);
            return false;
        }

        PullRequestLifecycle lifecycle = prOpt.get();
        String branch = lifecycle.getBranchName();
        String filePath = lifecycle.getFilePath();

        // 1. Lê a versão atual do arquivo na branch
        Map<String, String> currentFile = gitHubClient.getFileContent(repoName, filePath, branch);
        String currentContent = currentFile.getOrDefault("content", "");
        String fileSha = currentFile.get("sha");

        // 2. IA avalia a crítica e reescreve o código se concordar
        String adjustedCode = generateIterativeAdjustmentWithAi(currentContent, commentFeedback);

        // Se o código não necessitar de alterações (dúvida, pergunta, ou discordância
        // técnica)
        if (adjustedCode.equals(currentContent)) {
            log.info(
                    "💬 [CoderAgent] Nenhuma alteração de código necessária para o feedback. Respondendo na thread sem commit.");

            String technicalReply = String.format(
                    """
                            🤖 **[CoderAgent] Feedback analisado!**

                            > *"%s"*

                            O código atual já atende a esse critério ou não requer modificação no arquivo. Nenhuma alteração de código foi necessária para este ponto.

                            Se desejar um ajuste específico na lógica ou nos parâmetros, pode detalhar nesta thread! 👍
                            """,
                    commentFeedback);

            gitHubClient.replyToPrReviewComment(repoName, prNumber, commentId, technicalReply);

            // Dispara notificação por e-mail documentando a resposta
            emailNotificationService.sendCommentRepliedEmail(lifecycle, author, commentFeedback, technicalReply, false);
            return false;
        }

        // 3. Se concordou e houve alteração real, commita o ajuste na mesma branch
        String commitMsg = "fix(review): ajuste solicitado por @" + author + "\n\n" + commentFeedback;
        boolean committed = gitHubClient.commitFileChange(repoName, filePath, adjustedCode, commitMsg, branch, fileSha);

        if (committed) {
            // 4. Responde na thread e atualiza estado
            lifecycle.setStatus("CHANGES_REQUESTED");
            lifecycle.setAiReviewed(false); // Exige nova revisão do ReviewerAgent
            lifecycle.setAiApproved(false);
            savePrState(lifecycle);

            String reply = String.format(
                    """
                            🤖 **[CoderAgent] Alteração aplicada com sucesso!**

                            Concordo com a sugestão de revisão:
                            > *"%s"*

                            O ajuste foi implementado e commitado na branch `%s`.

                            ⏳ **Atenção:** Solicitando nova verificação para o `@ReviewerAgent` e aprovação final de @rafael-soares.
                            """,
                    commentFeedback, branch);

            gitHubClient.replyToPrReviewComment(repoName, prNumber, commentId, reply);

            // Dispara notificação por e-mail documentando o ajuste e o commit
            emailNotificationService.sendCommentRepliedEmail(lifecycle, author, commentFeedback, reply, true);
            return true;
        }

        return false;
    }

    /**
     * Persistência curta do ciclo de vida do PR. Isolada para não prender conexão
     * de banco durante I/O do GitHub e do LLM.
     */
    @Transactional
    public PullRequestLifecycle savePrState(PullRequestLifecycle pr) {
        return prRepository.save(pr);
    }

    private String generateCodeFixWithAi(String serviceName, String filePath, DiagnosticResultDTO incident,
            String currentCode, String stackTrace) {
        if (chatClientBuilder.isPresent()) {
            try {
                log.info("🛠️ [CoderAgent] Gerando patch via LLM provider={} timeout={}s maxTokens={}",
                        llmProperties.getProvider(), llmProperties.getCodegenTimeoutSeconds(),
                        llmProperties.getMaxTokens());

                var slice = com.keepguard.ms_ai_guardian.infrastructure.util.ScopedSourcePatcher.extract(
                        currentCode, filePath, incident.getErrorReason(), incident.getRootCause());
                String language = serviceName.contains("gateway") ? "Golang" : "Java Spring Boot";
                String scopeHint = slice.isWholeFile()
                        ? "o menor trecho possível do arquivo"
                        : "SOMENTE a função/método extraído abaixo (não a classe/arquivo inteiro)";

                log.info("🛠️ [CoderAgent] Escopo do hotfix: {} ({} chars de {} do arquivo)",
                        slice.isWholeFile() ? "arquivo inteiro (fallback)" : "função isolada",
                        slice.functionSource().length(), currentCode.length());

                String prompt = String.format(
                        """
                                Você é um programador especialista (%s) aplicando um HOTFIX PONTUAL.

                                Serviço: %s
                                Arquivo: %s
                                Erro (único fluxo a corrigir): %s
                                Causa Raiz: %s

                                --- TRECHO EM ESCOPO (%s) ---
                                %s

                                --- LOGS DO INCIDENTE ---
                                %s

                                Regras obrigatórias:
                                1. Corrija APENAS o caminho de execução que gerou este incidente. Não refatore a classe, não "limpe" o arquivo.
                                2. Preserve todo código fora desse fluxo (outros cases, panics de laboratório, simulate-bug, outros numberBug).
                                3. Se o erro for CODE_DEFECT_01 / divisão por zero na tarifação: trate rate/denominador <= 0 nesse fluxo e devolva taxa defensiva (sem panic / sem 500). Não apague os demais cenários.
                                4. Não reescreva ProcessBatchSMS se o incidente veio de ExecuteBugScenario (e vice-versa).
                                5. Responda APENAS com o mesmo método/função completo já corrigido. Sem markdown, sem package, sem o resto do arquivo, sem explicações.
                                """,
                        language, serviceName, filePath,
                        incident.getErrorReason(), incident.getRootCause(),
                        scopeHint, slice.functionSource(),
                        com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter.tail(stackTrace, 1200));

                String timeoutSentinel = "__LLM_TIMEOUT_OR_EMPTY__";
                String aiResult = com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter.callWithTimeout(
                        () -> chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content(),
                        llmProperties.getCodegenTimeoutSeconds(),
                        timeoutSentinel);
                if (aiResult == null || aiResult.isBlank() || timeoutSentinel.equals(aiResult)) {
                    log.warn("⚠️ [CoderAgent] LLM {} não devolveu patch (timeout ou vazio).",
                            llmProperties.getProvider());
                    return currentCode;
                }

                String patched = com.keepguard.ms_ai_guardian.infrastructure.util.ScopedSourcePatcher
                        .applyReplacement(slice, aiResult);
                if (!patched.equals(currentCode)) {
                    return patched;
                }
                log.warn("⚠️ [CoderAgent] LLM devolveu o mesmo código da main — sem diff para commitar.");
                return currentCode;
            } catch (Exception e) {
                log.warn("Falha no LLM ao gerar código: {}.", e.getMessage());
            }
        }

        log.warn("⚠️ [CoderAgent] Modelo de IA indisponível. PR de hotfix não será aberto neste ciclo.");
        return currentCode;
    }

    private String generateIterativeAdjustmentWithAi(String currentCode, String feedback) {
        if (chatClientBuilder.isPresent()) {
            try {
                String prompt = String.format(
                        """
                                Ajuste SOMENTE o trecho necessário segundo o code review. Não reescreva a classe inteira.

                                Feedback do Revisor: %s

                                --- CÓDIGO ATUAL ---
                                %s

                                Responda com o arquivo completo, mas altere só o fluxo citado no feedback. Preserve o restante. Sem markdown.
                                """,
                        feedback, currentCode);

                String aiResult = com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter.callWithTimeout(
                        () -> chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content(),
                        llmProperties.getCodegenTimeoutSeconds(),
                        currentCode);
                if (aiResult != null && !aiResult.isBlank() && !aiResult.equals(currentCode)) {
                    return aiResult.replaceAll("```[a-z]*\n?", "").replaceAll("```", "").trim();
                }
            } catch (Exception e) {
                log.warn("Falha no LLM de ajuste iterativo: {}", e.getMessage());
            }
        }

        // MOTOR DINÂMICO CONTEXTUAL PARA FEEDBACK DE CODE REVIEW (Sem chumbamento de
        // código ou strings específicas)
        if (feedback != null && !feedback.isBlank()) {
            String fb = feedback.toLowerCase();

            // 1. Diretiva Dinâmica: Remoção de comentários no código (// ou /* */)
            if ((fb.contains("coment") || fb.contains("comentário")) &&
                    (fb.contains("remov") || fb.contains("tirar") || fb.contains("apagar") || fb.contains("deletar")
                            || fb.contains("sem") || fb.contains("limp") || fb.contains("nao") || fb.contains("não"))) {

                // Remove dinamicamente todas as linhas de comentários explicativos de linha
                // única (// ...)
                String withoutSingleLineComments = currentCode.replaceAll("(?m)^[ \\t]*//.*\\R?", "");
                // Remove comentários inline residuais no fim da linha
                String withoutInlineComments = withoutSingleLineComments.replaceAll("(?m)[ \\t]+//.*$", "");
                // Remove blocos de comentários (/* ... */)
                String withoutBlockComments = withoutInlineComments.replaceAll("/\\*(?s:.*?)\\*/", "");

                // Se removeu comentários, retorna o código limpo dinamicamente
                if (!withoutBlockComments.equals(currentCode)) {
                    return withoutBlockComments.trim() + "\n";
                }
            }

            // 2. Diretiva Dinâmica: Remoção genérica de linha vazia ou duplicada
            if (fb.contains("linha") && (fb.contains("remov") || fb.contains("apagar") || fb.contains("deletar"))) {
                String cleaned = currentCode.replaceAll("(?m)^[ \\t]*//.*\\R?", "");
                if (!cleaned.equals(currentCode)) {
                    return cleaned.trim() + "\n";
                }
            }
        }

        return currentCode;
    }

    private String buildPrDescriptionMarkdown(
            DiagnosticResultDTO incident,
            String filePath,
            String rootCause,
            String action,
            BusinessAnalystAgentService.BusinessVerdict businessVerdict,
            SoftwareArchitectAgentService.ArchitecturalAssessment archAssessment,
            QaAutomationAgentService.QaCertificationReport qaReport) {

        String businessSection = businessVerdict != null
                ? String.format(
                        "### 👔 Análise de Negócio & Dados (BusinessAnalystAgent)\n- **Diagnóstico Funcional:** %s\n- **Impacto no Domínio:** %s\n",
                        businessVerdict.summary(), businessVerdict.businessContext())
                : "";

        String archSection = archAssessment != null
                ? String.format("""
                        ### 📐 Análise de Arquitetura & Sequência (SoftwareArchitectAgent)
                        - **Padrão Arquitetural:** `%s`
                        %s

                        #### 🔴 Fluxo Atual com Falha (Antes)
                        %s

                        #### 🟢 Fluxo Proposto Corrigido (Depois)
                        %s
                        """,
                        archAssessment.pattern(),
                        archAssessment.summary(),
                        archAssessment.currentFlowMermaid(),
                        archAssessment.proposedFlowMermaid())
                : "";

        String qaSection = qaReport != null
                ? String.format("""
                        ### 🧪 Certificação de Qualidade (QaAutomationAgent)
                        **Status Geral:** `%s`

                        %s
                        """,
                        qaReport.verdictText(),
                        qaReport.toMarkdownTable())
                : "";

        return String.format("""
                ## 🛡️ KeepGuard AI Guardian - Hotfix Automatizado & Squad Multi-Agent

                ### 📋 Detalhes do Incidente
                - **Serviço Afetado:** `%s`
                - **Pod:** `%s`
                - **Severidade:** `%s`
                - **Motivo:** `%s`
                - **Arquivo Modificado:** `%s`

                ### 🔍 Causa Raiz Diagnosticada
                > %s

                ### 💡 Ação Aplicada pelo CoderAgent
                Hotfix **pontual** no fluxo do incidente (não é refactor da classe).
                > %s

                ---
                %s
                ---
                %s
                ---
                %s
                ---
                ### 🚦 Fluxo de Aprovação e Quality Gate
                1. 🤖 **ReviewerAgent:** Validará a segurança e conformidade arquitetural.
                2. 👤 **Humano (Rafael):** Deve revisar o diff e realizar o **Merge** final.
                3. 🚀 **DeployerAgent:** Realizará o rollout automático no Kubernetes pós-Merge.
                """,
                incident.getServiceName(),
                incident.getPodName(),
                incident.getSeverity(),
                incident.getErrorReason(),
                filePath,
                rootCause,
                action,
                businessSection,
                archSection,
                qaSection);
    }
}
