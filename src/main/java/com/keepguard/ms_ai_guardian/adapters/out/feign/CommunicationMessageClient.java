package com.keepguard.ms_ai_guardian.adapters.out.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(
        name = "communication-message",
        url = "${app.communication.url:http://ms-communication:8082}",
        configuration = CommunicationMessageClientConfig.class
)
public interface CommunicationMessageClient {

    @PostMapping("/api/v1/messages/send")
    Map<String, Object> sendMessage(
            @RequestBody Map<String, Object> request,
            @RequestHeader("X-Company-Id") String companyId,
            @RequestHeader("X-Correlation-ID") String correlationId);
}
