package com.keepguard.ms_ai_guardian.adapters.out.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(
        name = "github-api",
        url = "${app.github.api-url:https://api.github.com}",
        configuration = GitHubClientConfig.class
)
public interface GitHubClient {

    @GetMapping("/repos/{owner}/{repo}/git/ref/heads/{branch}")
    String getBranchRef(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("branch") String branch,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept);

    @PostMapping("/repos/{owner}/{repo}/git/refs")
    void createRef(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept,
            @RequestBody Map<String, Object> body);

    @GetMapping("/repos/{owner}/{repo}/git/trees/{sha}")
    String getTree(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("sha") String sha,
            @RequestParam("recursive") int recursive,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept);

    @GetMapping("/repos/{owner}/{repo}/contents/{path}")
    String getFileContent(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("path") String path,
            @RequestParam("ref") String ref,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept);

    @PutMapping("/repos/{owner}/{repo}/contents/{path}")
    void putFileContent(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("path") String path,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept,
            @RequestBody Map<String, Object> body);

    @PostMapping("/repos/{owner}/{repo}/pulls")
    String createPullRequest(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept,
            @RequestBody Map<String, Object> body);

    @PostMapping("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
    void submitReview(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("pullNumber") int pullNumber,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept,
            @RequestBody Map<String, Object> body);

    @PostMapping("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
    void addIssueComment(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("issueNumber") int issueNumber,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept,
            @RequestBody Map<String, Object> body);

    @PostMapping("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
    void replyToReviewComment(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("pullNumber") int pullNumber,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept,
            @RequestBody Map<String, Object> body);

    @GetMapping("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
    String listReviewComments(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("pullNumber") int pullNumber,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept);

    @GetMapping("/repos/{owner}/{repo}/pulls/{pullNumber}")
    String getPullRequest(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @PathVariable("pullNumber") int pullNumber,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Accept") String accept);
}
