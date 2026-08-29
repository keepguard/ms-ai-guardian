package com.keepguard.ms_ai_guardian.application.port.out.notification;

import java.util.Map;
import java.util.UUID;

public interface NotificationPort {

    boolean send(NotificationCommand command);

    boolean sendHtmlTo(String recipient, String subject, String htmlBody, String serviceName,
            String logContext, UUID correlationId, boolean publishAudit);

    record NotificationCommand(
            NotificationKind kind,
            String subject,
            Map<String, String> variables,
            String serviceName,
            String logContext,
            UUID correlationId
    ) {}
}
