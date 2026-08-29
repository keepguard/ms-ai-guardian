package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.adapters.out.notification.EmailNotificationService;
import com.keepguard.ms_ai_guardian.domain.entity.GuardianAlertRecipient;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentAlertDelivery;
import com.keepguard.ms_ai_guardian.domain.enums.DeliveryOutcome;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentAlertDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlertFanoutService {

    private final AlertRecipientService recipientService;
    private final EmailNotificationService emailNotificationService;
    private final IncidentAlertDeliveryRepository deliveryRepository;

    @Value("${app.guardian.console-url:https://app.keepguard.com.br}")
    private String consoleUrl;

    public boolean fanoutOpened(Incident incident, List<IncidentActionSuggestion> suggestions) {
        String enabled = suggestions.stream()
                .filter(IncidentActionSuggestion::isEnabled)
                .map(s -> s.getLabel() + " (" + s.getRisk() + ")")
                .collect(Collectors.joining("<br/>"));
        if (enabled.isBlank()) {
            enabled = "Nenhuma ação habilitada pela política — abrir a mesa para detalhes.";
        }
        String cta = consoleUrl + "?tab=guardian";
        String html = mesaHtml(
                "Incidente aberto: " + incident.getServiceName(),
                incident,
                incident.getAiSummary() != null ? incident.getAiSummary() : incident.getAiRootCauseAnalysis(),
                "Opções habilitadas:<br/>" + enabled,
                "Abrir mesa SRE",
                cta);
        String subject = "[KeepGuard Guardian] " + incident.getServiceName() + " — "
                + (incident.getK8sConclusion() != null ? incident.getK8sConclusion() : incident.getErrorReason());
        return fanout(incident, "OPENED", subject, html);
    }

    public boolean fanoutNormalized(Incident incident) {
        String html = mesaHtml(
                "Serviço normalizado: " + incident.getServiceName(),
                incident,
                "O watcher confirmou saúde em " + incident.getHealthyStreak() + " varreduras consecutivas.",
                incident.getAiSummary() != null ? incident.getAiSummary() : "",
                "Ver incidente",
                consoleUrl + "?tab=guardian");
        String subject = "[KeepGuard Guardian] Normalizado: " + incident.getServiceName();
        return fanout(incident, "NORMALIZED", subject, html);
    }

    public boolean fanoutAction(Incident incident, String actionLabel, String outcome) {
        String html = mesaHtml(
                "Ação " + actionLabel + ": " + outcome,
                incident,
                "Uma ação humana foi aplicada no cluster.",
                "Resultado: " + outcome,
                "Ver incidente",
                consoleUrl + "?tab=guardian");
        String subject = "[KeepGuard Guardian] Ação " + actionLabel + " — " + incident.getServiceName();
        return fanout(incident, "ACTION", subject, html);
    }

    private boolean fanout(Incident incident, String kind, String subject, String html) {
        List<GuardianAlertRecipient> recipients = recipientService.listEnabledOrSeed();
        boolean anySent = false;
        UUID cid = incident.getId();
        for (GuardianAlertRecipient recipient : recipients) {
            boolean sent = emailNotificationService.sendHtmlTo(
                    recipient.getEmail(), subject, html, incident.getServiceName(),
                    kind, cid, true);
            deliveryRepository.save(IncidentAlertDelivery.builder()
                    .incidentId(incident.getId())
                    .email(recipient.getEmail())
                    .outcome(sent ? DeliveryOutcome.SENT : DeliveryOutcome.FAILED)
                    .kind(kind)
                    .correlationId(incident.getCorrelationId())
                    .build());
            anySent = anySent || sent;
        }
        return anySent;
    }

    private String mesaHtml(String title, Incident incident, String body, String extra, String ctaLabel, String ctaUrl) {
        return """
            <!DOCTYPE html>
            <html lang="pt-BR"><body style="font-family:Segoe UI,sans-serif;background:#f8fafc;padding:20px;">
            <div style="max-width:640px;margin:0 auto;background:#fff;border-radius:12px;padding:24px;border:1px solid #e2e8f0;">
              <h1 style="font-size:18px;">%s</h1>
              <p><strong>Serviço:</strong> %s &nbsp; <strong>Pod:</strong> %s</p>
              <p><strong>Conclusão K8s:</strong> %s</p>
              <p>%s</p>
              <p>%s</p>
              <p><a href="%s" style="display:inline-block;padding:10px 18px;background:#0f766e;color:#fff;text-decoration:none;border-radius:8px;">%s</a></p>
            </div></body></html>
            """.formatted(
                title,
                n(incident.getServiceName()),
                n(incident.getPodName()),
                n(incident.getK8sConclusion()),
                n(body).replace("\n", "<br/>"),
                n(extra).replace("\n", "<br/>"),
                ctaUrl,
                ctaLabel);
    }

    private static String n(String value) {
        return value == null ? "—" : value;
    }
}
