package com.keepguard.ms_ai_guardian.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parâmetros do LLM desacoplados do provedor.
 * Troca Ollama → OpenAI/Anthropic via env/propriedades, sem hardcode no código.
 */
@Getter
@Slf4j
@Component
public class GuardianLlmProperties {

    /** ollama | openai | anthropic | none */
    @Value("${app.guardian.llm.provider:ollama}")
    private String provider;

    @Value("${app.guardian.llm.timeout-seconds:45}")
    private int timeoutSeconds;

    @Value("${app.guardian.llm.codegen-timeout-seconds:90}")
    private int codegenTimeoutSeconds;

    @Value("${app.guardian.llm.max-tokens:256}")
    private int maxTokens;

    @Value("${app.guardian.llm.temperature:0.2}")
    private double temperature;

    public boolean isEnabled() {
        return provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider);
    }

    @PostConstruct
    void logActiveProvider() {
        log.info("LLM ativo: provider={} timeout={}s codegenTimeout={}s maxTokens={}",
                provider, timeoutSeconds, codegenTimeoutSeconds, maxTokens);
    }
}
