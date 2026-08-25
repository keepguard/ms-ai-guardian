package com.keepguard.ms_ai_guardian.infrastructure.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class RateLimiterService {

    // 1. GitHub API Rate Limiter: 10 requisições por segundo com burst de até 20
    private final Bucket gitHubBucket = Bucket.builder()
            .addLimit(Bandwidth.builder()
                    .capacity(20)
                    .refillGreedy(10, Duration.ofSeconds(1))
                    .build())
            .build();

    // 2. AI / LLM Rate Limiter: 5 inferências por segundo com burst de até 10
    private final Bucket aiPromptBucket = Bucket.builder()
            .addLimit(Bandwidth.builder()
                    .capacity(10)
                    .refillGreedy(5, Duration.ofSeconds(1))
                    .build())
            .build();

    // 3. Email Notification Rate Limiter: 20 e-mails por segundo com burst de até 40
    private final Bucket emailBucket = Bucket.builder()
            .addLimit(Bandwidth.builder()
                    .capacity(40)
                    .refillGreedy(20, Duration.ofSeconds(1))
                    .build())
            .build();

    public void acquireGitHubPermit() {
        log.debug("⏱️ [RateLimiter] Adquirindo permissão de vazão para GitHub REST API...");
        gitHubBucket.asBlocking().consumeUninterruptibly(1);
    }

    public void acquireAiPromptPermit() {
        log.debug("⏱️ [RateLimiter] Adquirindo permissão de vazão para LLM Engine...");
        aiPromptBucket.asBlocking().consumeUninterruptibly(1);
    }

    public void acquireEmailPermit() {
        log.debug("⏱️ [RateLimiter] Adquirindo permissão de vazão para Email Gateway...");
        emailBucket.asBlocking().consumeUninterruptibly(1);
    }
}
