package com.keepguard.ms_ai_guardian.infrastructure.redis;

import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisAlertCooldownAdapterTest {

    private RedisAlertCooldownAdapter adapter;

    @BeforeEach
    void setUp() {
        GuardianProperties properties = new GuardianProperties();
        adapter = new RedisAlertCooldownAdapter(null, properties);
    }

    @Test
    void firstAcquireSucceedsLocally() {
        assertTrue(adapter.tryAcquire("storm:opened:test@mail.com", 15));
    }

    @Test
    void secondAcquireBlockedWithinCooldown() {
        String scope = "opened:ms-auth:user@test.com";
        assertTrue(adapter.tryAcquire(scope, 15));
        assertFalse(adapter.tryAcquire(scope, 15));
    }

    @Test
    void blankScopeAlwaysAcquires() {
        assertTrue(adapter.tryAcquire("", 15));
        assertTrue(adapter.tryAcquire("   ", 15));
    }
}
