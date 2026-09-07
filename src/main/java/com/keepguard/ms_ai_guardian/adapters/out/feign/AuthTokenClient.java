package com.keepguard.ms_ai_guardian.adapters.out.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(
        name = "auth-token",
        url = "${auth.base-url:http://ms-auth:8081}",
        configuration = AuthTokenClientConfig.class
)
public interface AuthTokenClient {

    @GetMapping("/api/v1/auth/oauth/runtime/secret")
    RuntimeSecretResponse getRuntimeSecret(
            @RequestParam("clientId") String clientId,
            @RequestHeader("X-Company-Id") String companyId,
            @RequestHeader("X-Auth-Client-Secret-Base") String secretBase);

    @PostMapping("/api/v1/auth/oauth/token")
    TokenResponse requestToken(
            @RequestHeader("X-Company-Id") String companyId,
            @RequestBody Map<String, String> body);

    record RuntimeSecretResponse(String clientId, String secretEncrypted, String status) {}

    record TokenResponse(String accessToken, long expiresIn) {}
}
