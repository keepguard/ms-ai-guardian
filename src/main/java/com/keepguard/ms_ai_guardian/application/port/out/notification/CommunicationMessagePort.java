package com.keepguard.ms_ai_guardian.application.port.out.notification;

import java.util.Map;

public interface CommunicationMessagePort {

    void sendMessage(Map<String, Object> payload, String companyId, String correlationId);
}
