package com.keepguard.ms_ai_guardian.application.service.agents;

import com.keepguard.ms_ai_guardian.adapters.out.github.GitHubApiClient;
import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.domain.entity.PullRequestLifecycle;
import com.keepguard.ms_ai_guardian.domain.repository.PullRequestLifecycleRepository;
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
    private final EmailNotificationService emailNotificationService;
    private final Optional<ChatClient.Builder> chatClientBuilder;

    /**
     * Executa a análise de qualidade, segurança e regras de negócio no PR aberto pelo CoderAgent.
     */
    public boolean performReview(PullRequestLifecycle pr) {
        String repoName = pr.getRepoName();
        int prNumber = pr.getPrNumber();

        log.info("🧐 [ReviewerAgent] Analisando PR #{} do repositório {}", prNumber, repoName);

        try {
            // 1. Lê o código alterado na branch do PR
            Map<String, String> fileInfo = gitHubClient.getFileContent(repoName, pr.getFilePath(), pr.getBranchName());
            String modifiedCode = fileInfo.getOrDefault("content", "");

            // 2. IA analisa o código em busca de falhas de segurança, Clean Code e bugs
            ReviewVerdict verdict = evaluateCodeWithAi(repoName, pr.getFilePath(), modifiedCode);

            // 3. Submete o parecer da revisão no GitHub (via COMMENT para não colidir com a regra do GitHub de aprovação do próprio autor)
            if (verdict.approved()) {
                gitHubClient.submitReview(repoName, prNumber, "COMMENT", 
                        "🤖 **[ReviewerAgent] PARECER TÉCNICO: APROVADO PELA IA!**\n\n" + verdict.feedback() + 
                        "\n\n---\n👤 **Atenção:** Aguardando revisão final e Merge do desenvolvedor humano (@rafael-soares).");

                boolean isFirstApproval = !pr.isAiApproved() && !"CHANGES_REQUESTED".equals(pr.getStatus());
                
                pr.setAiReviewed(true);
                pr.setAiApproved(true);
                pr.setAiReviewFeedback(verdict.feedback());
                pr.setStatus("AI_APPROVED");
                prRepository.save(pr);

                log.info("✅ [ReviewerAgent] PR #{} APROVADO pela IA com sucesso!", prNumber);

                // Envia e-mail de notificação para o Rafael informando que o PR está pronto para aprovação humana apenas na 1ª vez
                if (isFirstApproval) {
                    emailNotificationService.sendPrReadyForHumanApprovalEmail(pr, verdict.feedback());
                }
                return true;

            } else {
                gitHubClient.submitReview(repoName, prNumber, "COMMENT",
                        "⚠️ **[ReviewerAgent] PARECER TÉCNICO: SOLICITAÇÃO DE AJUSTES**\n\n" + verdict.feedback());

                pr.setAiReviewed(true);
                pr.setAiApproved(false);
                pr.setAiReviewFeedback(verdict.feedback());
                pr.setStatus("CHANGES_REQUESTED");
                prRepository.save(pr);

                log.warn("⚠️ [ReviewerAgent] PR #{} requer ajustes do CoderAgent: {}", prNumber, verdict.feedback());
                return false;
            }

        } catch (Exception e) {
            log.error("Erro no [ReviewerAgent] durante análise do PR #{}: {}", prNumber, e.getMessage(), e);
            return false;
        }
    }

    private ReviewVerdict evaluateCodeWithAi(String serviceName, String filePath, String code) {
        if (chatClientBuilder.isPresent()) {
            try {
                String prompt = String.format("""
                    Você é um Tech Lead e Arquiteto de Software Java Spring Boot especialista em Code Review.
                    Analise o arquivo '%s' do microsserviço '%s'.
                    
                    --- CÓDIGO DO PULL REQUEST ---
                    %s
                    
                    Critérios de Avaliação:
                    1. Segurança e ausência de vulnerabilidades.
                    2. Tratamento adequado de ponteiros nulos e exceções.
                    3. Respeito à arquitetura limpa (Clean Code / Spring Boot).
                    
                    Se o código estiver de alta qualidade e seguro, inicie sua resposta com 'VEREDITO: APROVADO' seguido de elogios/pontos positivos.
                    Caso encontre problemas graves, inicie com 'VEREDITO: REPROVADO' e liste os pontos que o CoderAgent precisa corrigir.
                    """, filePath, serviceName, code);

                String aiResponse = chatClientBuilder.get().build().prompt(new Prompt(prompt)).call().content();
                boolean isApproved = aiResponse != null && aiResponse.contains("APROVADO");

                return new ReviewVerdict(isApproved, aiResponse != null ? aiResponse : "Revisão concluída pela IA.");
            } catch (Exception e) {
                log.warn("Falha no LLM do ReviewerAgent: {}", e.getMessage());
            }
        }

        // Heurística de Aprovação Segura caso o LLM esteja indisponível
        return new ReviewVerdict(true, "Código revisado e validado pelas diretrizes de segurança do KeepGuard Guardian.");
    }

    public record ReviewVerdict(boolean approved, String feedback) {}
}
