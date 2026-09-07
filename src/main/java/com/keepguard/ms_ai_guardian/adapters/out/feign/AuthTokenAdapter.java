package com.keepguard.ms_ai_guardian.adapters.out.feign;

import com.keepguard.ms_ai_guardian.application.port.out.auth.AuthTokenPort;
import com.keepguard.ms_ai_guardian.infrastructure.oauth.OAuthSecretCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AuthTokenAdapter implements AuthTokenPort {

    private final AuthTokenClient authTokenClient;
    private final String clientId;
    private final String secretBase;
    private final Duration renewBefore;
    private final Map<String, CacheEntry> tokens = new ConcurrentHashMap<>();
    private final Map<String, SecretEntry> secrets = new ConcurrentHashMap<>();

    public AuthTokenAdapter(
            AuthTokenClient authTokenClient,
            @Value("${auth.client-id:ms-ai-guardian}") String clientId,
            @Value("${auth.secret-base:}") String secretBase,
            @Value("${auth.token-renew-before-seconds:600}") int renewBeforeSeconds) {
        this.authTokenClient = authTokenClient;
        this.clientId = StringUtils.hasText(clientId) ? clientId.trim() : "ms-ai-guardian";
        this.secretBase = secretBase == null ? "" : secretBase.trim();
        this.renewBefore = Duration.ofSeconds(Math.max(renewBeforeSeconds, 1));
    }

    public boolean configured() {
        return StringUtils.hasText(secretBase);
    }

    @Override
    public Optional<String> getToken(UUID companyId) {
        if (!configured() || companyId == null) {
            return Optional.empty();
        }
        String key = companyId.toString();
        CacheEntry cached = tokens.get(key);
        if (cached != null && Instant.now().plus(renewBefore).isBefore(cached.expiry())) {
            return Optional.of(cached.token());
        }
        synchronized (this) {
            cached = tokens.get(key);
            if (cached != null && Instant.now().plus(renewBefore).isBefore(cached.expiry())) {
                return Optional.of(cached.token());
            }
            try {
                SecretEntry secret = resolveSecret(companyId);
                AuthTokenClient.TokenResponse token = authTokenClient.requestToken(
                        companyId.toString(),
                        Map.of(
                                "grantType", "client_credentials",
                                "clientId", secret.clientId(),
                                "clientSecret", secret.plain()));
                if (token == null || !StringUtils.hasText(token.accessToken())) {
                    return Optional.empty();
                }
                long ttl = token.expiresIn() > 0 ? token.expiresIn() : 3600;
                tokens.put(key, new CacheEntry(token.accessToken().trim(), Instant.now().plusSeconds(ttl)));
                return Optional.of(token.accessToken().trim());
            } catch (Exception e) {
                log.warn("Falha ao obter token OAuth para LLM (company={}): {}", companyId, e.getMessage());
                return Optional.empty();
            }
        }
    }

    private SecretEntry resolveSecret(UUID companyId) {
        String key = companyId.toString();
        SecretEntry cached = secrets.get(key);
        if (cached != null) {
            return cached;
        }
        AuthTokenClient.RuntimeSecretResponse body = authTokenClient.getRuntimeSecret(
                clientId, companyId.toString(), secretBase);
        if (body == null || !StringUtils.hasText(body.secretEncrypted())) {
            throw new IllegalStateException("OAuth client sem secret cifrado; recrie o client " + clientId);
        }
        String plain = OAuthSecretCrypto.decrypt(secretBase, body.secretEncrypted());
        String resolvedClient = StringUtils.hasText(body.clientId()) ? body.clientId().trim() : clientId;
        SecretEntry entry = new SecretEntry(resolvedClient, plain);
        secrets.put(key, entry);
        return entry;
    }

    record CacheEntry(String token, Instant expiry) {}

    record SecretEntry(String clientId, String plain) {}
}
