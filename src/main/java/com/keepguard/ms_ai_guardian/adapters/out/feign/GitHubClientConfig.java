package com.keepguard.ms_ai_guardian.adapters.out.feign;

import feign.Logger;
import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

public class GitHubClientConfig {

    @Bean
    public Logger.Level githubLoggerLevel() {
        return Logger.Level.BASIC;
    }

    @Bean
    public Request.Options githubRequestOptions() {
        return new Request.Options(Duration.ofSeconds(5), Duration.ofSeconds(20), true);
    }
}
