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
    private String consoleUrl = "https://app-core.keepguard.com.br";
    private String defaultRecipient = "";
    private String tenantId = "";
    private String approverGithub = "human";
    private String approverDisplayName = "time";
    private Redis redis = new Redis();
    private RateLimit rateLimit = new RateLimit();
    private Storm storm = new Storm();

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

    @Getter
    @Setter
    public static class Storm {
        /** Percentual mínimo de deployments indisponíveis para considerar tempestade. */
        private int deploymentThresholdPercent = 40;
        /** Mínimo de deployments afetados (além do percentual). */
        private int minAffectedDeployments = 5;
        /** Varreduras consecutivas antes de alertar falha de infra isolada (não tempestade). */
        private int infraAlertConfirmScans = 2;
        /** TTL do estado de tempestade no Redis (segundos). */
        private int stateTtlSeconds = 7200;
    }
}
