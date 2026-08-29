package com.keepguard.ms_ai_guardian;

import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GuardianProperties.class)
public class MsAiGuardianApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsAiGuardianApplication.class, args);
    }
}
