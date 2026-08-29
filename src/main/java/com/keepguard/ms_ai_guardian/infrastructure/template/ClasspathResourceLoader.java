package com.keepguard.ms_ai_guardian.infrastructure.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ClasspathResourceLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String classpathLocation) {
        return cache.computeIfAbsent(classpathLocation, this::read);
    }

    private String read(String location) {
        try {
            ClassPathResource resource = new ClassPathResource(location);
            if (!resource.exists()) {
                throw new IllegalStateException("Recurso não encontrado no classpath: " + location);
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler recurso " + location, e);
        }
    }
}
