package com.keepguard.ms_ai_guardian.infrastructure.redis;

import com.keepguard.ms_ai_guardian.application.port.out.cache.DistributedLockPort;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDistributedLockAdapter implements DistributedLockPort {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final GuardianProperties properties;
    private final ConcurrentHashMap<String, String> localFallback = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String lockName, String ownerId, int ttlSeconds) {
        String key = key(lockName);
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, ownerId, Duration.ofSeconds(ttlSeconds));
            if (Boolean.TRUE.equals(acquired)) {
                log.info("Lock Redis adquirido: {}", lockName);
                return true;
            }
            log.warn("Lock Redis ocupado: {}", lockName);
            return false;
        } catch (Exception e) {
            log.warn("Redis indisponível para lock '{}', fallback em memória: {}", lockName, e.getMessage());
            return localFallback.putIfAbsent(lockName, ownerId) == null;
        }
    }

    @Override
    public void release(String lockName, String ownerId) {
        String key = key(lockName);
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), ownerId);
        } catch (Exception e) {
            log.warn("Falha ao liberar lock Redis '{}': {}", lockName, e.getMessage());
            localFallback.remove(lockName, ownerId);
        }
        localFallback.remove(lockName, ownerId);
    }

    private String key(String lockName) {
        return properties.getRedis().getKeyPrefix() + ":lock:" + lockName;
    }
}
