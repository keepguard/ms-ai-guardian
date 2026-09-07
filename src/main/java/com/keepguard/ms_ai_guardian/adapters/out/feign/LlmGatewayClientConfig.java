package com.keepguard.ms_ai_guardian.adapters.out.feign;

import feign.Logger;
import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

public class LlmGatewayClientConfig {

    @Bean
    public Logger.Level llmGatewayLoggerLevel() {
        return Logger.Level.BASIC;
    }

    @Bean
    public Request.Options llmGatewayRequestOptions() {
        return new Request.Options(Duration.ofSeconds(5), Duration.ofSeconds(95), true);
    }
}
