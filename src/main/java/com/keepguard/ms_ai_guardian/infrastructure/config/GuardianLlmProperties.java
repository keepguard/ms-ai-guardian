package com.keepguard.ms_ai_guardian.infrastructure.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parâmetros do LLM desacoplados do provedor.
 * Troca Ollama → OpenAI/Anthropic via env/propriedades, sem hardcode no código.
 */
@Getter
@Component
public class GuardianLlmProperties {

    /** ollama | openai | anthropic | none */
    @Value("${app.guardian.llm.provider:ollama}")
    private String provider;

    @Value("${app.guardian.llm.timeout-seconds:20}")
    private int timeoutSeconds;

    @Value("${app.guardian.llm.codegen-timeout-seconds:45}")
    private int codegenTimeoutSeconds;

    @Value("${app.guardian.llm.max-tokens:256}")
    private int maxTokens;

    @Value("${app.guardian.llm.temperature:0.2}")
    private double temperature;

    public boolean isEnabled() {
        return provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider);
    }
}
