package com.keepguard.ms_ai_guardian.application.port.out.cache;

public interface AlertCooldownPort {

    /**
     * @return true se o cooldown ainda não foi adquirido (pode enviar e-mail)
     */
    boolean tryAcquire(String scopeKey, int cooldownMinutes);
}
