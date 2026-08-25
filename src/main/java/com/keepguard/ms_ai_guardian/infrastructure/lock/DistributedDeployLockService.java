package com.keepguard.ms_ai_guardian.infrastructure.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DistributedDeployLockService {

    // Mapa de locks concorrentes em memória com TTL de expiração de segurança (10 minutos)
    private final ConcurrentHashMap<String, LockMetadata> activeDeployLocks = new ConcurrentHashMap<>();

    private record LockMetadata(String serviceName, String lockedBy, Instant expiresAt) {}

    /**
     * Tenta adquirir a trava exclusiva de deploy para o microsserviço.
     * Retorna true se adquiriu o lock com sucesso, ou false se o deploy já estiver em andamento.
     */
    public synchronized boolean tryAcquireDeployLock(String serviceName, String deployId) {
        cleanExpiredLocks();

        if (activeDeployLocks.containsKey(serviceName)) {
            LockMetadata current = activeDeployLocks.get(serviceName);
            log.warn("🔒 [DistributedDeployLock] Serviço '{}' já possui deploy em andamento (Iniciado por: {}). Aguardando liberação para evitar concorrência!",
                    serviceName, current.lockedBy());
            return false;
        }

        Instant expiresAt = Instant.now().plusSeconds(600); // 10 minutos de timeout máximo
        activeDeployLocks.put(serviceName, new LockMetadata(serviceName, deployId, expiresAt));
        log.info("🔓 [DistributedDeployLock] Lock exclusivo de deploy adquirido com sucesso para o serviço '{}' (Deploy ID: {})",
                serviceName, deployId);
        return true;
    }

    /**
     * Libera a trava de deploy após a conclusão do rollout no Kubernetes.
     */
    public synchronized void releaseDeployLock(String serviceName) {
        activeDeployLocks.remove(serviceName);
        log.info("🔓 [DistributedDeployLock] Lock de deploy liberado para o serviço '{}'. Próximos deploys estão autorizados.", serviceName);
    }

    private void cleanExpiredLocks() {
        Instant now = Instant.now();
        activeDeployLocks.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }
}
