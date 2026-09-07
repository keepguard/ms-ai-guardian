package com.keepguard.ms_ai_guardian.adapters.out.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.adapters.out.feign.GitHubClient;
import com.keepguard.ms_ai_guardian.application.port.out.cache.RateLimiterPort;
import com.keepguard.ms_ai_guardian.application.port.out.github.GitHubPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubApiClient implements GitHubPort {

    private static final String ACCEPT = "application/vnd.github+json";

    private final ObjectMapper objectMapper;
    private final RateLimiterPort rateLimiterService;
    private final GitHubClient gitHubClient;

    @Value("${app.github.token:${GITHUB_TOKEN:}}")
    private String githubToken;

    @Value("${app.github.owner:keepguard}")
    private String githubOwner;

    private String getAuthHeader() {
        if (rateLimiterService != null) {
            rateLimiterService.acquireGitHubPermit();
        }
        if (githubToken == null || githubToken.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN não configurado no AI Guardian.");
        }
        return "Bearer " + githubToken.trim();
    }

    @Override
    public String getBranchSha(String repoName, String branchName) {
        try {
            String response = gitHubClient.getBranchRef(githubOwner, repoName, branchName, getAuthHeader(), ACCEPT);
            JsonNode root = objectMapper.readTree(response);
            return root.path("object").path("sha").asText();
        } catch (Exception e) {
            log.error("Erro ao obter SHA da branch {}/{}: {}", repoName, branchName, e.getMessage());
            throw new RuntimeException("Falha ao obter branch SHA: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean createBranch(String repoName, String newBranchName, String baseSha) {
        try {
            gitHubClient.createRef(
                    githubOwner,
                    repoName,
                    getAuthHeader(),
                    ACCEPT,
                    Map.of("ref", "refs/heads/" + newBranchName, "sha", baseSha));
            log.info("🌿 Branch '{}' criada com sucesso no repositório {}/{}", newBranchName, githubOwner, repoName);
            return true;
        } catch (Exception e) {
            log.error("Erro ao criar branch {} no repo {}: {}", newBranchName, repoName, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listSourceFilePaths(String repoName, String branch) {
        try {
            String sha = getBranchSha(repoName, branch);
            String response = gitHubClient.getTree(githubOwner, repoName, sha, 1, getAuthHeader(), ACCEPT);
            JsonNode root = objectMapper.readTree(response);
            List<String> paths = new ArrayList<>();
            JsonNode tree = root.path("tree");
            if (tree.isArray()) {
                for (JsonNode item : tree) {
                    if (!"blob".equals(item.path("type").asText())) {
                        continue;
                    }
                    String path = item.path("path").asText();
                    if (isSourcePath(path)) {
                        paths.add(path);
                    }
                }
            }
            return paths;
        } catch (Exception e) {
            log.warn("Não foi possível listar a árvore de {}/{}: {}", githubOwner, repoName, e.getMessage());
            return List.of();
        }
    }

    private static boolean isSourcePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/vendor/") || lower.contains("/node_modules/") || lower.contains("/target/")
                || lower.contains("/.git/") || lower.contains("/dist/")) {
            return false;
        }
        return lower.endsWith(".go") || lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".kts");
    }

    @Override
    public Map<String, String> getFileContent(String repoName, String filePath, String branch) {
        try {
            String response = gitHubClient.getFileContent(githubOwner, repoName, filePath, branch, getAuthHeader(), ACCEPT);
            JsonNode root = objectMapper.readTree(response);
            String sha = root.path("sha").asText();
            String encodedContent = root.path("content").asText().replaceAll("\\s", "");
            String content = new String(Base64.getDecoder().decode(encodedContent), StandardCharsets.UTF_8);
            return Map.of("sha", sha, "content", content);
        } catch (Exception e) {
            log.warn("Arquivo {} não encontrado ou erro na leitura em {}/{}: {}", filePath, githubOwner, repoName, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public boolean commitFileChange(String repoName, String filePath, String newContent, String commitMessage, String branch, String fileSha) {
        try {
            String base64Content = Base64.getEncoder().encodeToString(newContent.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> body = new HashMap<>();
            body.put("message", commitMessage);
            body.put("content", base64Content);
            body.put("branch", branch);
            if (fileSha != null && !fileSha.isBlank()) {
                body.put("sha", fileSha);
            }
            gitHubClient.putFileContent(githubOwner, repoName, filePath, getAuthHeader(), ACCEPT, body);
            log.info("💾 Commit realizado com sucesso no arquivo {} na branch {}", filePath, branch);
            return true;
        } catch (Exception e) {
            log.error("Erro ao commitar alteração no arquivo {} em {}/{}: {}", filePath, githubOwner, repoName, e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> createPullRequest(String repoName, String title, String bodyMarkdown, String headBranch, String baseBranch) {
        try {
            String response = gitHubClient.createPullRequest(
                    githubOwner,
                    repoName,
                    getAuthHeader(),
                    ACCEPT,
                    Map.of("title", title, "body", bodyMarkdown, "head", headBranch, "base", baseBranch));
            JsonNode root = objectMapper.readTree(response);
            int prNumber = root.path("number").asInt();
            String htmlUrl = root.path("html_url").asText();
            log.info("🚀 Pull Request #{} aberto com sucesso: {}", prNumber, htmlUrl);
            return Map.of("prNumber", prNumber, "htmlUrl", htmlUrl);
        } catch (Exception e) {
            log.error("Erro ao criar Pull Request em {}/{}: {}", githubOwner, repoName, e.getMessage());
            throw new RuntimeException("Falha ao abrir Pull Request: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean submitReview(String repoName, int prNumber, String event, String commentBody) {
        try {
            gitHubClient.submitReview(
                    githubOwner,
                    repoName,
                    prNumber,
                    getAuthHeader(),
                    ACCEPT,
                    Map.of("body", commentBody, "event", event));
            log.info("🧐 Revisão ({}) submetida no PR #{} do repo {}", event, prNumber, repoName);
            return true;
        } catch (Exception e) {
            log.error("Erro ao submeter revisão no PR #{}: {}", prNumber, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean addComment(String repoName, int prNumber, String commentText) {
        try {
            gitHubClient.addIssueComment(
                    githubOwner, repoName, prNumber, getAuthHeader(), ACCEPT, Map.of("body", commentText));
            log.info("💬 Comentário adicionado no PR #{}", prNumber);
            return true;
        } catch (Exception e) {
            log.error("Erro ao comentar no PR #{}: {}", prNumber, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean replyToPrReviewComment(String repoName, int prNumber, String commentId, String replyText) {
        try {
            gitHubClient.replyToReviewComment(
                    githubOwner,
                    repoName,
                    prNumber,
                    getAuthHeader(),
                    ACCEPT,
                    Map.of("body", replyText, "in_reply_to", Long.parseLong(commentId)));
            log.info("💬 Resposta inline enviada na thread do comentário #{} no PR #{}", commentId, prNumber);
            return true;
        } catch (Exception e) {
            log.warn("Erro ao responder na thread inline do comentário #{}, fallback para comentário geral: {}", commentId, e.getMessage());
            return addComment(repoName, prNumber, replyText);
        }
    }

    @Override
    public List<Map<String, String>> getPrReviewComments(String repoName, int prNumber) {
        try {
            String response = gitHubClient.listReviewComments(githubOwner, repoName, prNumber, getAuthHeader(), ACCEPT);
            JsonNode root = objectMapper.readTree(response);
            List<Map<String, String>> comments = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    comments.add(Map.of(
                            "id", item.path("id").asText(),
                            "body", item.path("body").asText(),
                            "author", item.path("user").path("login").asText(),
                            "path", item.path("path").asText()
                    ));
                }
            }
            return comments;
        } catch (Exception e) {
            log.error("Erro ao buscar comentários do PR #{}: {}", prNumber, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> getPullRequestStatus(String repoName, int prNumber) {
        try {
            String response = gitHubClient.getPullRequest(githubOwner, repoName, prNumber, getAuthHeader(), ACCEPT);
            JsonNode root = objectMapper.readTree(response);
            boolean merged = root.path("merged").asBoolean(false);
            String state = root.path("state").asText();
            String mergedBy = root.path("merged_by").path("login").asText("human");
            return Map.of("merged", merged, "state", state, "mergedBy", mergedBy);
        } catch (Exception e) {
            log.error("Erro ao checar status do PR #{}: {}", prNumber, e.getMessage());
            return Map.of("merged", false, "state", "open", "mergedBy", "");
        }
    }
}
