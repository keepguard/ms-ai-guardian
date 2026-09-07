package com.keepguard.ms_ai_guardian.adapters.out.feign;

import com.keepguard.ms_ai_guardian.application.port.out.notification.CommunicationMessagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CommunicationMessageAdapter implements CommunicationMessagePort {

    private final CommunicationMessageClient client;

    @Override
    public void sendMessage(Map<String, Object> payload, String companyId, String correlationId) {
        client.sendMessage(payload, companyId, correlationId);
    }
}
