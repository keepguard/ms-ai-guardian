package com.keepguard.ms_ai_guardian.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.guardian")
public class GuardianProperties {

    private String namespace = "keepguard";
    private boolean watcherEnabled = true;
    private int scanIntervalMs = 60_000;
    private int antiFlappingCooldownMinutes = 15;
    private int healthyStreakRequired = 3;
    private int maxAlertRecipients = 20;
    private String consoleUrl = "https://app.keepguard.com.br";
    private String defaultRecipient = "";
    private String tenantId = "";
    private String approverGithub = "human";
    private String approverDisplayName = "time";
    private Redis redis = new Redis();
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class Redis {
        private String keyPrefix = "guardian";
        private int lockTtlSeconds = 600;
        private int idempotencyTtlSeconds = 86_400;
        private int promptCacheTtlSeconds = 300;
        private int llmCacheTtlSeconds = 3_600;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int githubPerSecond = 10;
        private int llmPerSecond = 5;
        private int emailPerSecond = 20;
    }
}
