package com.keepguard.ms_ai_guardian.infrastructure.config;

import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.infrastructure.classification.ClassificationCatalog;
import com.keepguard.ms_ai_guardian.infrastructure.prompt.CompositePromptCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogSeeder implements ApplicationRunner {

    private final CompositePromptCatalog promptCatalog;
    private final ClassificationCatalog classificationCatalog;

    @Override
    public void run(ApplicationArguments args) {
        int seeded = 0;
        for (String key : PromptKeys.classpathKeys()) {
            try {
                if (promptCatalog.seedOrRefresh(key) != null) {
                    seeded++;
                }
            } catch (Exception e) {
                log.warn("Não foi possível semear prompt {}: {}", key, e.getMessage());
            }
        }
        if (seeded > 0) {
            log.info("Prompts semeados ou atualizados no banco: {}", seeded);
        }
        try {
            classificationCatalog.seedIfEmpty();
        } catch (Exception e) {
            log.warn("Não foi possível semear regras de classificação: {}", e.getMessage());
        }
    }
}
