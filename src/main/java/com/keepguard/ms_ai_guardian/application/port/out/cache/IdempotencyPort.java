package com.keepguard.ms_ai_guardian.application.port.out.cache;

public interface IdempotencyPort {

    /**
     * @return true se a chave ainda não existia (primeira execução)
     */
    boolean tryBegin(String key, int ttlSeconds);
}
