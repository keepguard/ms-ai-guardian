package com.keepguard.ms_ai_guardian.application.port.out.github;

import java.util.List;
import java.util.Map;

public interface GitHubPort {

    String getBranchSha(String repoName, String branchName);

    boolean createBranch(String repoName, String newBranchName, String baseSha);

    List<String> listSourceFilePaths(String repoName, String branch);

    Map<String, String> getFileContent(String repoName, String filePath, String branch);

    boolean commitFileChange(String repoName, String filePath, String newContent, String commitMessage,
            String branch, String fileSha);

    Map<String, Object> createPullRequest(String repoName, String title, String bodyMarkdown,
            String headBranch, String baseBranch);

    boolean submitReview(String repoName, int prNumber, String event, String commentBody);

    boolean addComment(String repoName, int prNumber, String commentText);

    boolean replyToPrReviewComment(String repoName, int prNumber, String commentId, String replyText);

    List<Map<String, String>> getPrReviewComments(String repoName, int prNumber);

    Map<String, Object> getPullRequestStatus(String repoName, int prNumber);
}
