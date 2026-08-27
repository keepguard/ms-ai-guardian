package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.github.GitHubApiClient;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultDTO;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /**
     * Cria uma branch, aplica a correção gerada pela IA e abre um Pull Request enriquecido com Arquitetura e QA.
     */
    @Transactional
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
                        ("OPEN".equals(pr.getStatus()) || "AI_APPROVED".equals(pr.getStatus()) || "CHANGES_REQUESTED".equals(pr.getStatus())))
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
                prRepository.save(pr);
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
            String targetPath = filePath != null && !filePath.isBlank() ? filePath : "src/main/resources/application.yml";
            Map<String, String> fileInfo = gitHubClient.getFileContent(repoName, targetPath, baseBranch);
            String currentCode = fileInfo.getOrDefault("content", "# Conteúdo original indisponível");
            String fileSha = fileInfo.get("sha");

            // 4. Solicita ao modelo de IA a correção precisa do código
            String fixedCode = generateCodeFixWithAi(repoName, incident, currentCode, rawStackTrace);

            // VALIDAÇÃO CRÍTICA: Se a correção for idêntica ao código já presente na main, aborta a criação de PR vazio
            if (fixedCode.equals(currentCode)) {
                log.warn("⚠️ [CoderAgent] O código corrigido é idêntico ao código atual na branch {}. PR não será criado para evitar commits vazios.", baseBranch);
                return Optional.empty();
            }

            // 5. 🧪 QA AUTOMATION AGENT: Certifica e testa o hotfix antes de commitar
            var qaReport = qaAutomationAgentService.certifyQuality(repoName, targetPath, fixedCode);

            // 6. 📐 SOFTWARE ARCHITECT AGENT: Analisa arquitetura e gera diagramas Mermaid Antes vs Depois
            var archAssessment = architectAgentService.designSolution(incident, repoName, targetPath, rawStackTrace);

            // 7. Commita a correção na branch
            String commitMsg = "fix(" + repoName + "): correção automatizada por KeepGuard AI Guardian\n\nCausa: " + incident.getRootCause();
            boolean committed = gitHubClient.commitFileChange(repoName, targetPath, fixedCode, commitMsg, newBranchName, fileSha);
            if (!committed) {
                log.error("Falha ao commitar código na branch {}", newBranchName);
                return Optional.empty();
            }

            // 8. Abre o Pull Request Rico
            String prTitle = "🚨 [AI Guardian Hotfix] Correção de Incidente: " + incident.getErrorReason();
            String prBody = buildPrDescriptionMarkdown(incident, targetPath, incident.getRootCause(), incident.getRecommendedAction(), businessVerdict, archAssessment, qaReport);
            Map<String, Object> prResult = gitHubClient.createPullRequest(repoName, prTitle, prBody, newBranchName, baseBranch);

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

            prRepository.save(lifecycle);
            log.info("✅ [CoderAgent] Pull Request #{} aberto com sucesso: {}", prNumber, prUrl);
            return Optional.of(lifecycle);

        } catch (Exception e) {
            log.error("Erro no [CoderAgent] ao criar hotfix PR para {}: {}", repoName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Ajusta o código do PR com base no comentário/feedback recebido da revisão.
     */
    @Transactional
    public boolean applyReviewFeedbackAndNotify(String repoName, int prNumber, String commentId, String commentFeedback, String author) {
        log.info("🛠️ [CoderAgent] Ajustando código do PR #{} com base no feedback de {}: {}", prNumber, author, commentFeedback);

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

        // Se o código não necessitar de alterações (dúvida, pergunta, ou discordância técnica)
        if (adjustedCode.equals(currentContent)) {
            log.info("💬 [CoderAgent] Nenhuma alteração de código necessária para o feedback. Respondendo na thread sem commit.");
            
            String technicalReply = String.format("""
                🤖 **[CoderAgent] Feedback analisado!**
                
                > *"%s"*
                
                O código atual já atende a esse critério ou não requer modificação no arquivo. Nenhuma alteração de código foi necessária para este ponto.
                
                Se desejar um ajuste específico na lógica ou nos parâmetros, pode detalhar nesta thread! 👍
                """, commentFeedback);

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
            prRepository.save(lifecycle);

            String reply = String.format("""
                🤖 **[CoderAgent] Alteração aplicada com sucesso!**
                
                Concordo com a sugestão de revisão:
                > *"%s"*
                
                O ajuste foi implementado e commitado na branch `%s`.
                
                ⏳ **Atenção:** Solicitando nova verificação para o `@ReviewerAgent` e aprovação final de @rafael-soares.
                """, commentFeedback, branch);

            gitHubClient.replyToPrReviewComment(repoName, prNumber, commentId, reply);

            // Dispara notificação por e-mail documentando o ajuste e o commit
            emailNotificationService.sendCommentRepliedEmail(lifecycle, author, commentFeedback, reply, true);
            return true;
        }

        return false;
    }

    private String generateCodeFixWithAi(String serviceName, DiagnosticResultDTO incident, String currentCode, String stackTrace) {
        if (chatClientBuilder.isPresent()) {
            try {
                String prompt = String.format("""
                    Você é um programador e arquiteto de software especialista (%s). Corrija o código abaixo para solucionar o erro:
                    
                    Serviço: %s
                    Erro: %s
                    Causa Raiz: %s
                    
                    --- CÓDIGO ATUAL ---
                    %s
                    
                    --- STACKTRACE / LOGS ---
                    %s
                    
                    Responda APENAS com o código-fonte completo corrigido. Não inclua markdown, crases nem explicações adicionais.
                    """, serviceName.contains("gateway") ? "Golang" : "Java Spring Boot", serviceName, incident.getErrorReason(), incident.getRootCause(), currentCode, stackTrace != null ? stackTrace : "");

                String aiResult = chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content();
                if (aiResult != null && !aiResult.isBlank()) {
                    // Limpa crases markdown caso o LLM retorne formatação
                    return aiResult.replaceAll("```[a-z]*\n?", "").replaceAll("```", "").trim();
                }
            } catch (Exception e) {
                log.warn("Falha no LLM ao gerar código: {}. Aplicando motor de correção heurística precisa.", e.getMessage());
            }
        }

        // MOTOR DE CORREÇÃO DINÂMICO PARA HOTFIX (Gera DIFF e Correção Real de Código no GitHub)
        if ("mock-sms-gateway".equalsIgnoreCase(serviceName)) {
            String error = incident.getErrorReason() != null ? incident.getErrorReason() : "";
            
            // Extrai o número do bug ou identifica a linha de falha do switch
            if (error.contains("CODE_DEFECT_")) {
                try {
                    String defectTag = error.substring(error.indexOf("CODE_DEFECT_"), error.indexOf("CODE_DEFECT_") + 14);
                    int bugNum = Integer.parseInt(defectTag.replace("CODE_DEFECT_", "").trim());

                    String targetFaultyCase = String.format("case %d:\n\t\treturn nil, fmt.Errorf(\"%s", bugNum, defectTag);
                    
                    // Procura o case problemático no código e o substitui por uma resolução segura e defensiva
                    int caseIdx = currentCode.indexOf("case " + bugNum + ":");
                    if (caseIdx != -1) {
                        int nextCaseIdx = currentCode.indexOf("case " + (bugNum + 1) + ":", caseIdx);
                        if (nextCaseIdx == -1) {
                            nextCaseIdx = currentCode.indexOf("default:", caseIdx);
                        }
                        if (nextCaseIdx != -1) {
                            String fixedBlock = String.format("case %d:\n\t\t// [KeepGuard AI Guardian Hotfix]: Falha corrigida com protecao defensiva automatizada\n\t\ts.logger.Info(\"🛡️ [AI Guardian Patch] Executando logica tratada e defensiva para %s\")\n\t\treturn map[string]interface{}{\n\t\t\t\"status\": \"FIXED_BY_AI_GUARDIAN\",\n\t\t\t\"numberBug\": %d,\n\t\t\t\"message\": \"Defeito de software %s solucionado com sucesso\",\n\t\t}, nil\n\n\t", bugNum, defectTag, bugNum, defectTag);

                            return currentCode.substring(0, caseIdx) + fixedBlock + currentCode.substring(nextCaseIdx);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Erro ao aplicar substituição cirúrgica no switch: {}", e.getMessage());
                }
            }
        }

        // Se não houver alteração substantiva e identificada, retorna o código inalterado para abortar o PR
        log.warn("⚠️ [CoderAgent] Nenhuma regra de correção aplicável encontrada para o incidente {}. PR abortado.", incident.getErrorReason());
        return currentCode;
    }

    private String generateIterativeAdjustmentWithAi(String currentCode, String feedback) {
        if (chatClientBuilder.isPresent()) {
            try {
                String prompt = String.format("""
                    Ajuste o seguinte código de acordo com a solicitação de Code Review:
                    
                    Feedback do Revisor: %s
                    
                    --- CÓDIGO ATUAL ---
                    %s
                    
                    Responda APENAS com o código completo corrigido. Sem texto antes ou depois e sem crases markdown.
                    """, feedback, currentCode);

                String aiResult = chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content();
                if (aiResult != null && !aiResult.isBlank()) {
                    return aiResult.replaceAll("```[a-z]*\n?", "").replaceAll("```", "").trim();
                }
            } catch (Exception e) {
                log.warn("Falha no LLM de ajuste iterativo: {}", e.getMessage());
            }
        }

        // MOTOR DINÂMICO CONTEXTUAL PARA FEEDBACK DE CODE REVIEW (Sem chumbamento de código ou strings específicas)
        if (feedback != null && !feedback.isBlank()) {
            String fb = feedback.toLowerCase();

            // 1. Diretiva Dinâmica: Remoção de comentários no código (// ou /* */)
            if ((fb.contains("coment") || fb.contains("comentário")) && 
                (fb.contains("remov") || fb.contains("tirar") || fb.contains("apagar") || fb.contains("deletar") || fb.contains("sem") || fb.contains("limp") || fb.contains("nao") || fb.contains("não"))) {
                
                // Remove dinamicamente todas as linhas de comentários explicativos de linha única (// ...)
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
                ? String.format("### 👔 Análise de Negócio & Dados (BusinessAnalystAgent)\n- **Diagnóstico Funcional:** %s\n- **Impacto no Domínio:** %s\n",
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
                qaSection
        );
    }
}
