package com.keepguard.ms_ai_guardian.infrastructure.redis;

import com.keepguard.ms_ai_guardian.application.port.out.cache.IdempotencyPort;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private final StringRedisTemplate redisTemplate;
    private final GuardianProperties properties;
    private final ConcurrentHashMap<String, Boolean> localFallback = new ConcurrentHashMap<>();

    @Override
    public boolean tryBegin(String key, int ttlSeconds) {
        String redisKey = properties.getRedis().getKeyPrefix() + ":idem:" + key;
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("Redis indisponível para idempotência '{}': {}", key, e.getMessage());
            return localFallback.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }
}
