package com.keepguard.ms_ai_guardian.infrastructure.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Impede que stack traces gigantes e gerações intermináveis do LLM
 * (Ollama ou API paga) bloqueiem o pipeline de alerta.
 * Timeout vem de app.guardian.llm.timeout-seconds / codegen-timeout-seconds.
 */
public final class LlmContextLimiter {

    public static final int LOG_CHARS = 2500;
    public static final int DEFAULT_TIMEOUT_SECONDS = 20;

    private LlmContextLimiter() {}

    public static String tail(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return "...[truncado]\n" + text.substring(text.length() - maxChars);
    }

    public static String callWithTimeout(Supplier<String> llmCall, int timeoutSeconds, String fallback) {
        try {
            String result = CompletableFuture.supplyAsync(llmCall)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .join();
            return (result == null || result.isBlank()) ? fallback : result;
        } catch (Exception e) {
            return fallback;
        }
    }
}
