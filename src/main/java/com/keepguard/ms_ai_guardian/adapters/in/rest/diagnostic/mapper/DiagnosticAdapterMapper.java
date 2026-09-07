package com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.mapper;

import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.request.ManualDiagnoseRequestDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.response.DiagnosticEnqueueResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.response.DiagnosticResultResponseDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnoseCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticEnqueueViewDTO;
import com.keepguard.ms_ai_guardian.application.dto.DiagnosticResultViewDTO;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticAdapterMapper {

    public DiagnoseCommandDTO toCommand(ManualDiagnoseRequestDTO request) {
        String namespace = request.getNamespace() != null ? request.getNamespace() : "keepguard";
        String serviceName = request.getServiceName() != null ? request.getServiceName() : request.getPodName();
        String errorReason = request.getErrorReason() != null ? request.getErrorReason() : "MANUAL_TRIGGER";
        return DiagnoseCommandDTO.builder()
                .namespace(namespace)
                .podName(request.getPodName())
                .serviceName(serviceName)
                .errorReason(errorReason)
                .forceSendEmail(request.isForceSendEmail())
                .build();
    }

    public DiagnoseCommandDTO toAsyncCommand(ManualDiagnoseRequestDTO request) {
        DiagnoseCommandDTO command = toCommand(request);
        if (request.getErrorReason() == null) {
            command.setErrorReason("MANUAL_ASYNC_TRIGGER");
        }
        return command;
    }

    public DiagnosticResultResponseDTO toResponse(DiagnosticResultViewDTO view) {
        if (view == null) {
            return null;
        }
        return DiagnosticResultResponseDTO.builder()
                .incidentId(view.getIncidentId())
                .podName(view.getPodName())
                .namespace(view.getNamespace())
                .serviceName(view.getServiceName())
                .severity(view.getSeverity())
                .errorReason(view.getErrorReason())
                .rootCause(view.getRootCause())
                .recommendedAction(view.getRecommendedAction())
                .technicalDetails(view.getTechnicalDetails())
                .notificationSent(view.isNotificationSent())
                .build();
    }

    public DiagnosticEnqueueResponseDTO toEnqueueResponse(DiagnosticEnqueueViewDTO view) {
        if (view == null) {
            return null;
        }
        return DiagnosticEnqueueResponseDTO.builder()
                .trackingId(view.getTrackingId())
                .namespace(view.getNamespace())
                .podName(view.getPodName())
                .serviceName(view.getServiceName())
                .errorReason(view.getErrorReason())
                .forceSendEmail(view.isForceSendEmail())
                .enqueuedTimestamp(view.getEnqueuedTimestamp())
                .build();
    }
}
