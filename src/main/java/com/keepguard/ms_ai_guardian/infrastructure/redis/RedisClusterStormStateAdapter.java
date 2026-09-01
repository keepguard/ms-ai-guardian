package com.keepguard.ms_ai_guardian.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.application.dto.ClusterStormState;
import com.keepguard.ms_ai_guardian.application.port.out.cache.ClusterStormStatePort;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisClusterStormStateAdapter implements ClusterStormStatePort {

    private final StringRedisTemplate redisTemplate;
    private final GuardianProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ClusterStormState> localFallback = new ConcurrentHashMap<>();

    @Override
    public Optional<ClusterStormState> get(String namespace) {
        String key = redisKey(namespace);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ClusterStormState.class));
        } catch (Exception e) {
            log.debug("Falha ao ler storm state de Redis para {}: {}", namespace, e.getMessage());
            return Optional.ofNullable(localFallback.get(namespace));
        }
    }

    @Override
    public void save(String namespace, ClusterStormState state, int ttlSeconds) {
        localFallback.put(namespace, state);
        String key = redisKey(namespace);
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(Math.max(ttlSeconds, 60)));
        } catch (Exception e) {
            log.warn("Falha ao salvar storm state no Redis para {}: {}", namespace, e.getMessage());
        }
    }

    @Override
    public void clear(String namespace) {
        localFallback.remove(namespace);
        try {
            redisTemplate.delete(redisKey(namespace));
        } catch (Exception e) {
            log.debug("Falha ao limpar storm state no Redis para {}: {}", namespace, e.getMessage());
        }
    }

    private String redisKey(String namespace) {
        return properties.getRedis().getKeyPrefix() + ":storm:" + namespace;
    }
}
