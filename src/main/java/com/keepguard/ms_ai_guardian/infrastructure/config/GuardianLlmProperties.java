package com.keepguard.ms_ai_guardian.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parâmetros do LLM desacoplados do provedor.
 * Default: none (heurística). Ligar LLM: APP_GUARDIAN_LLM_PROVIDER=gateway.
 */
@Getter
@Slf4j
@Component
public class GuardianLlmProperties {

    /** gateway | ollama | openai | anthropic | none */
    @Value("${app.guardian.llm.provider:none}")
    private String provider;

    @Value("${app.guardian.llm.gateway-url:http://srv-llm-gateway:8650}")
    private String gatewayUrl;

    /** Token Bearer opcional (M2M). Vazio = só headers de tenant na rede interna. */
    @Value("${app.guardian.llm.gateway-bearer-token:}")
    private String gatewayBearerToken;

    /** Se preenchido, o complete força este providerId; senão o gateway escolhe o ativo. */
    @Value("${app.guardian.llm.provider-id:}")
    private String providerId;

    /** Se preenchido, força o modelo; senão o gateway usa model_default do provedor. */
    @Value("${app.guardian.llm.model:}")
    private String model;

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
        log.info("LLM ativo: provider={} gatewayUrl={} timeout={}s codegenTimeout={}s maxTokens={}",
                provider, gatewayUrl, timeoutSeconds, codegenTimeoutSeconds, maxTokens);
    }
}
