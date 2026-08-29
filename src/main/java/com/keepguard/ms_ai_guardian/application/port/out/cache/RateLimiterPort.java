package com.keepguard.ms_ai_guardian.application.port.out.cache;

public interface RateLimiterPort {

    enum Bucket {
        GITHUB, LLM, EMAIL
    }

    void acquire(Bucket bucket);

    default void acquireGitHubPermit() {
        acquire(Bucket.GITHUB);
    }

    default void acquireAiPromptPermit() {
        acquire(Bucket.LLM);
    }

    default void acquireEmailPermit() {
        acquire(Bucket.EMAIL);
    }
}
