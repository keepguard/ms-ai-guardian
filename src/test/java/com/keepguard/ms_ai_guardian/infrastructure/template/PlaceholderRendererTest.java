package com.keepguard.ms_ai_guardian.infrastructure.template;

import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderRendererTest {

    @Test
    void replacesPlaceholders() {
        String out = PlaceholderRenderer.render("Olá {{name}}", Map.of("name", "Rafael"));
        assertEquals("Olá Rafael", out);
    }

    @Test
    void htmlEscapesAndBreaksLines() {
        assertEquals("a&lt;b<br/>c", PlaceholderRenderer.html("a<b\nc"));
    }

    @Test
    void classpathPromptsExist() {
        ClasspathResourceLoader loader = new ClasspathResourceLoader();
        for (String key : PromptKeys.classpathKeys()) {
            String body = loader.load("prompts/" + key + ".st");
            assertFalse(body.isBlank(), "prompt vazio: " + key);
        }
    }

    @Test
    void sreInvestigatePromptRequiresPortugueseNarrative() {
        ClasspathResourceLoader loader = new ClasspathResourceLoader();
        String body = loader.load("prompts/sre.investigate.st");
        assertTrue(body.contains("português brasileiro"));
        assertTrue(body.contains("{{errorReasonLabel}}"));
        assertTrue(body.contains("{{conclusionLabel}}"));
    }

    @Test
    void emailTemplatesExist() {
        ClasspathResourceLoader loader = new ClasspathResourceLoader();
        assertTrue(loader.load("templates/email/pr-opened.html").contains("{{prNumber}}"));
        assertTrue(loader.load("templates/email/mesa.html").contains("{{ctaUrl}}"));
    }
}
