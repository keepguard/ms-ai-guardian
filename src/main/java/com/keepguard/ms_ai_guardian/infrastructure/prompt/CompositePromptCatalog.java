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
import java.util.Optional;

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
        return seedOrRefresh(key);
    }

    /**
     * Semear prompt novo ou atualizar o ativo quando o arquivo do classpath mudou.
     */
    public PromptTemplate seedOrRefresh(String key) {
        String body = loadClasspath(key);
        String checksum = DigestUtils.md5DigestAsHex(body.getBytes(StandardCharsets.UTF_8));
        Optional<PromptTemplate> existing = repository.findFirstByPromptKeyAndStatusOrderByUpdatedAtDesc(key, ACTIVE);
        if (existing.isEmpty()) {
            return repository.save(PromptTemplate.builder()
                    .promptKey(key)
                    .version("1")
                    .body(body)
                    .status(ACTIVE)
                    .checksum(checksum)
                    .build());
        }
        return refreshIfChanged(existing.get(), body, checksum);
    }

    private PromptTemplate refreshIfChanged(PromptTemplate existing, String body, String checksum) {
        if (checksum.equals(existing.getChecksum()) && body.equals(existing.getBody())) {
            return null;
        }
        existing.setBody(body);
        existing.setChecksum(checksum);
        existing.setVersion(nextVersion(existing.getVersion()));
        PromptTemplate saved = repository.save(existing);
        evictPromptCache(existing.getPromptKey());
        log.info("Prompt {} atualizado para a versão {}", existing.getPromptKey(), saved.getVersion());
        return saved;
    }

    private void evictPromptCache(String key) {
        try {
            redisTemplate.delete(properties.getRedis().getKeyPrefix() + ":prompt:" + key);
        } catch (Exception e) {
            log.debug("Não foi possível invalidar cache do prompt {}: {}", key, e.getMessage());
        }
    }

    private static String nextVersion(String current) {
        if (current == null || current.isBlank() || "classpath".equals(current)) {
            return "2";
        }
        try {
            return String.valueOf(Integer.parseInt(current) + 1);
        } catch (NumberFormatException e) {
            return current + ".pt";
        }
    }

    public static String[] keys() {
        return PromptKeys.classpathKeys();
    }
}
