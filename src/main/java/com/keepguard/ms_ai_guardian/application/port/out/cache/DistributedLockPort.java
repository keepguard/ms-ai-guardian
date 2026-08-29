package com.keepguard.ms_ai_guardian.application.port.out.cache;

public interface DistributedLockPort {

    boolean tryAcquire(String lockName, String ownerId, int ttlSeconds);

    void release(String lockName, String ownerId);
}
