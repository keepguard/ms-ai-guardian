package com.keepguard.ms_ai_guardian.application.port.out.llm;

public final class PromptKeys {

    public static final String CODER_HOTFIX = "coder.hotfix";
    public static final String CODER_REVIEW_ADJUST = "coder.review-adjust";
    public static final String REVIEWER_HOTFIX_SCOPE = "reviewer.hotfix-scope";
    public static final String SRE_INVESTIGATE = "sre.investigate";
    public static final String GITHUB_CODER_NO_CHANGE = "github.coder-no-change";
    public static final String GITHUB_CODER_CHANGE_APPLIED = "github.coder-change-applied";
    public static final String GITHUB_REVIEWER_APPROVED = "github.reviewer-approved";
    public static final String GITHUB_REVIEWER_REJECTED = "github.reviewer-rejected";
    public static final String PR_BODY = "github.pr-body";
    public static final String ARCH_CURRENT_FLOW = "architecture.current-flow";
    public static final String ARCH_PROPOSED_FLOW = "architecture.proposed-flow";
    public static final String ARCH_SUMMARY = "architecture.summary";

    private PromptKeys() {}

    public static String[] classpathKeys() {
        return new String[] {
                CODER_HOTFIX, CODER_REVIEW_ADJUST, REVIEWER_HOTFIX_SCOPE, SRE_INVESTIGATE,
                GITHUB_CODER_NO_CHANGE, GITHUB_CODER_CHANGE_APPLIED,
                GITHUB_REVIEWER_APPROVED, GITHUB_REVIEWER_REJECTED, PR_BODY,
                ARCH_CURRENT_FLOW, ARCH_PROPOSED_FLOW, ARCH_SUMMARY
        };
    }
}
