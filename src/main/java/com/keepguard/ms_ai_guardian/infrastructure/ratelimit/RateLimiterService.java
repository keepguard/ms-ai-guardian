package com.keepguard.ms_ai_guardian.infrastructure.ratelimit;

import com.keepguard.ms_ai_guardian.application.port.out.cache.RateLimiterPort;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import io.github.bucket4j.Bandwidth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Service
public class RateLimiterService implements RateLimiterPort {

    private final StringRedisTemplate redisTemplate;
    private final GuardianProperties properties;
    private final Map<RateLimiterPort.Bucket, io.github.bucket4j.Bucket> localBuckets =
            new EnumMap<>(RateLimiterPort.Bucket.class);

    public RateLimiterService(StringRedisTemplate redisTemplate, GuardianProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        GuardianProperties.RateLimit rl = properties.getRateLimit();
        localBuckets.put(RateLimiterPort.Bucket.GITHUB, local(rl.getGithubPerSecond()));
        localBuckets.put(RateLimiterPort.Bucket.LLM, local(rl.getLlmPerSecond()));
        localBuckets.put(RateLimiterPort.Bucket.EMAIL, local(rl.getEmailPerSecond()));
    }

    @Override
    public void acquire(Bucket bucket) {
        int limit = limitOf(bucket);
        String key = properties.getRedis().getKeyPrefix() + ":rl:" + bucket.name().toLowerCase()
                + ":" + Instant.now().getEpochSecond();
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(2));
            }
            if (count != null && count > limit) {
                sleepUntilNextWindow();
                acquire(bucket);
            }
        } catch (Exception e) {
            log.debug("Redis indisponível para rate-limit {}, usando Bucket4j local: {}", bucket, e.getMessage());
            localBuckets.get(bucket).asBlocking().consumeUninterruptibly(1);
        }
    }

    private int limitOf(Bucket bucket) {
        GuardianProperties.RateLimit rl = properties.getRateLimit();
        return switch (bucket) {
            case GITHUB -> rl.getGithubPerSecond();
            case LLM -> rl.getLlmPerSecond();
            case EMAIL -> rl.getEmailPerSecond();
        };
    }

    private static io.github.bucket4j.Bucket local(int perSecond) {
        int capacity = Math.max(perSecond * 2, 4);
        return io.github.bucket4j.Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(perSecond, Duration.ofSeconds(1))
                        .build())
                .build();
    }

    private static void sleepUntilNextWindow() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
