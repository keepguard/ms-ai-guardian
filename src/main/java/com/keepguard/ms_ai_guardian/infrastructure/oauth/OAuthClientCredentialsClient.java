package com.keepguard.ms_ai_guardian.infrastructure.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OAuthClientCredentialsClient {

    private final String authBaseUrl;
    private final String clientId;
    private final String secretBase;
    private final Duration renewBefore;
    private final RestClient restClient;
    private final Map<String, CacheEntry> tokens = new ConcurrentHashMap<>();
    private final Map<String, SecretEntry> secrets = new ConcurrentHashMap<>();

    public OAuthClientCredentialsClient(
            @Value("${auth.base-url:http://ms-auth:8081}") String authBaseUrl,
            @Value("${auth.client-id:ms-ai-guardian}") String clientId,
            @Value("${auth.secret-base:}") String secretBase,
            @Value("${auth.token-renew-before-seconds:600}") int renewBeforeSeconds) {
        this(authBaseUrl, clientId, secretBase, renewBeforeSeconds, RestClient.builder()
                .baseUrl(trimSlash(authBaseUrl))
                .build());
    }

    OAuthClientCredentialsClient(
            String authBaseUrl,
            String clientId,
            String secretBase,
            int renewBeforeSeconds,
            RestClient restClient) {
        this.authBaseUrl = trimSlash(authBaseUrl);
        this.clientId = StringUtils.hasText(clientId) ? clientId.trim() : "ms-ai-guardian";
        this.secretBase = secretBase == null ? "" : secretBase.trim();
        this.renewBefore = Duration.ofSeconds(Math.max(renewBeforeSeconds, 1));
        this.restClient = restClient;
    }

    public boolean configured() {
        return StringUtils.hasText(authBaseUrl) && StringUtils.hasText(secretBase);
    }

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
                TokenResponse token = requestToken(companyId, secret.clientId(), secret.plain());
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
        String uri = UriComponentsBuilder.fromPath("/api/v1/auth/oauth/runtime/secret")
                .queryParam("clientId", clientId)
                .toUriString();
        RuntimeSecretResponse body = restClient.get()
                .uri(uri)
                .header("X-Company-Id", companyId.toString())
                .header("X-Auth-Client-Secret-Base", secretBase)
                .retrieve()
                .body(RuntimeSecretResponse.class);
        if (body == null || !StringUtils.hasText(body.secretEncrypted())) {
            throw new IllegalStateException("OAuth client sem secret cifrado; recrie o client " + clientId);
        }
        String plain = OAuthSecretCrypto.decrypt(secretBase, body.secretEncrypted());
        String resolvedClient = StringUtils.hasText(body.clientId()) ? body.clientId().trim() : clientId;
        SecretEntry entry = new SecretEntry(resolvedClient, plain);
        secrets.put(key, entry);
        return entry;
    }

    private TokenResponse requestToken(UUID companyId, String resolvedClientId, String secret) {
        try {
            return restClient.post()
                    .uri("/api/v1/auth/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Company-Id", companyId.toString())
                    .body(Map.of(
                            "grantType", "client_credentials",
                            "clientId", resolvedClientId,
                            "clientSecret", secret))
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("ms-auth token HTTP " + e.getStatusCode().value(), e);
        }
    }

    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    record RuntimeSecretResponse(String clientId, String secretEncrypted, String status) {}

    record TokenResponse(String accessToken, long expiresIn) {}

    record CacheEntry(String token, Instant expiry) {}

    record SecretEntry(String clientId, String plain) {}
}
