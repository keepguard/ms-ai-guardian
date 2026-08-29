package com.keepguard.ms_ai_guardian.application.port.in;

public interface HandlePrEventPort {

    void onPullRequest(String repoName, int prNumber, String action, boolean merged, String sender);

    void onComment(String repoName, int prNumber, String commentId, String body, String author);

    void scanOpenPullRequests();
}
