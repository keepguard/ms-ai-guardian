package com.keepguard.ms_ai_guardian;

import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = "com.keepguard.ms_ai_guardian.adapters.out.feign")
@EnableJpaRepositories(basePackages = "com.keepguard.ms_ai_guardian.infrastructure.persistence.spring")
@EnableConfigurationProperties(GuardianProperties.class)
public class MsAiGuardianApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsAiGuardianApplication.class, args);
    }
}
