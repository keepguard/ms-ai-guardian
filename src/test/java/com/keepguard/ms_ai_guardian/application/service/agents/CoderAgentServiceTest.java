package com.keepguard.ms_ai_guardian.application.service.agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoderAgentServiceTest {

    @Test
    void removesCommentsWhenReviewAsks() {
        String code = """
                public int div(int a, int b) {
                    // guarda
                    return a / b;
                }
                """;
        String cleaned = CoderAgentService.applyHeuristicReviewDirectives(code, "pode remover o comentário");
        assertFalse(cleaned.contains("guarda"));
        assertTrue(cleaned.contains("return a / b"));
    }

    @Test
    void keepsCodeWhenFeedbackIsUnrelated() {
        String code = "int x = 1;\n";
        assertEquals(code, CoderAgentService.applyHeuristicReviewDirectives(code, "por que esse nome?"));
    }
}
