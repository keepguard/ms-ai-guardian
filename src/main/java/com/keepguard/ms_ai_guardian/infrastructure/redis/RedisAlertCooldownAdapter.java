package com.keepguard.ms_ai_guardian.infrastructure.redis;

import com.keepguard.ms_ai_guardian.application.port.out.cache.AlertCooldownPort;
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
public class RedisAlertCooldownAdapter implements AlertCooldownPort {

    private final StringRedisTemplate redisTemplate;
    private final GuardianProperties properties;
    private final ConcurrentHashMap<String, Long> localFallback = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String scopeKey, int cooldownMinutes) {
        if (scopeKey == null || scopeKey.isBlank() || cooldownMinutes <= 0) {
            return true;
        }
        String redisKey = properties.getRedis().getKeyPrefix() + ":alert-cd:" + scopeKey;
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", Duration.ofMinutes(cooldownMinutes));
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.debug("Redis indisponível para cooldown '{}': {}", scopeKey, e.getMessage());
            long now = System.currentTimeMillis();
            long windowMs = cooldownMinutes * 60_000L;
            Long last = localFallback.get(scopeKey);
            if (last != null && now - last < windowMs) {
                return false;
            }
            localFallback.put(scopeKey, now);
            return true;
        }
    }
}
