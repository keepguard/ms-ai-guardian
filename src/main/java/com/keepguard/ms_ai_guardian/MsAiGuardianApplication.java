package com.keepguard.ms_ai_guardian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MsAiGuardianApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsAiGuardianApplication.class, args);
    }
}
