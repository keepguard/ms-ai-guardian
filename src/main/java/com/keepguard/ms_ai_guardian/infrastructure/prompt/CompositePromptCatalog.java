package com.keepguard.ms_ai_guardian.infrastructure.prompt;

import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptCatalogPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.domain.entity.PromptTemplate;
import com.keepguard.ms_ai_guardian.domain.repository.PromptTemplateRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.template.ClasspathResourceLoader;
import com.keepguard.ms_ai_guardian.infrastructure.template.PlaceholderRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompositePromptCatalog implements PromptCatalogPort {

    private static final String ACTIVE = "ACTIVE";

    private final PromptTemplateRepository repository;
    private final ClasspathResourceLoader classpath;
    private final StringRedisTemplate redisTemplate;
    private final GuardianProperties properties;

    @Override
    public String render(String key, Map<String, String> variables) {
        return PlaceholderRenderer.render(snapshot(key).body(), variables);
    }

    @Override
    public PromptSnapshot snapshot(String key) {
        String cacheKey = properties.getRedis().getKeyPrefix() + ":prompt:" + key;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                int sep = cached.indexOf('\n');
                if (sep > 0) {
                    return new PromptSnapshot(key, cached.substring(0, sep), cached.substring(sep + 1));
                }
            }
        } catch (Exception e) {
            log.debug("Cache de prompt indisponível: {}", e.getMessage());
        }

        PromptSnapshot snap = repository.findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(key, ACTIVE)
                .map(row -> new PromptSnapshot(key, row.getVersion(), row.getBody()))
                .orElseGet(() -> new PromptSnapshot(key, "classpath", loadClasspath(key)));

        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    snap.version() + "\n" + snap.body(),
                    Duration.ofSeconds(properties.getRedis().getPromptCacheTtlSeconds()));
        } catch (Exception e) {
            log.debug("Não foi possível cachear prompt {}: {}", key, e.getMessage());
        }
        return snap;
    }

    public String loadClasspath(String key) {
        return classpath.load("prompts/" + key + ".st");
    }

    public PromptTemplate seedIfAbsent(String key) {
        if (repository.existsByPromptKeyAndStatus(key, ACTIVE)) {
            return null;
        }
        String body = loadClasspath(key);
        return repository.save(PromptTemplate.builder()
                .promptKey(key)
                .version("1")
                .body(body)
                .status(ACTIVE)
                .checksum(DigestUtils.md5DigestAsHex(body.getBytes(StandardCharsets.UTF_8)))
                .build());
    }

    public static String[] keys() {
        return PromptKeys.classpathKeys();
    }
}
