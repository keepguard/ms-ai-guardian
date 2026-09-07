package com.keepguard.ms_ai_guardian.adapters.out.feign;

import feign.Logger;
import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

public class CommunicationMessageClientConfig {

    @Bean
    public Logger.Level communicationMessageLoggerLevel() {
        return Logger.Level.BASIC;
    }

    @Bean
    public Request.Options communicationMessageRequestOptions() {
        return new Request.Options(Duration.ofSeconds(2), Duration.ofSeconds(8), true);
    }
}
