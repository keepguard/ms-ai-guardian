package com.keepguard.ms_ai_guardian.application.service.pr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlePrEventUseCaseTest {

    @Test
    void ignoresBotAndAgentComments() {
        assertTrue(HandlePrEventUseCase.isBotComment("keepguard-bot", "qualquer"));
        assertTrue(HandlePrEventUseCase.isBotComment("rafael", "🤖 **[CoderAgent] Feedback analisado!**"));
        assertFalse(HandlePrEventUseCase.isBotComment("rafael", "pode remover o comentário"));
    }
}
