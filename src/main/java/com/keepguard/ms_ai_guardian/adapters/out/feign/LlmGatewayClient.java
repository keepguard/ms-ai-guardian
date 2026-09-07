package com.keepguard.ms_ai_guardian.adapters.out.feign;

import com.keepguard.ms_ai_guardian.infrastructure.llm.GatewayLlmDtos;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "llm-gateway",
        url = "${app.guardian.llm.gateway-url:http://srv-llm-gateway:8650}",
        configuration = LlmGatewayClientConfig.class
)
public interface LlmGatewayClient {

    @PostMapping("/api/v1/llm/complete")
    GatewayLlmDtos.CompleteResponse complete(
            @RequestBody GatewayLlmDtos.CompleteRequest body,
            @RequestHeader(value = "X-Company-Id", required = false) String companyId,
            @RequestHeader("X-Correlation-ID") String correlationId,
            @RequestHeader(value = "Authorization", required = false) String authorization);
}
