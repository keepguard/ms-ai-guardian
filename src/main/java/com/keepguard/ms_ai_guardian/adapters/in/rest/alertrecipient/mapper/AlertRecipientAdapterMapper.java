package com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.mapper;

import com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.dto.request.AlertRecipientUpsertRequestDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.dto.response.AlertRecipientResponseDTO;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientUpsertCommandDTO;
import com.keepguard.ms_ai_guardian.application.dto.AlertRecipientViewDTO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AlertRecipientAdapterMapper {

    public AlertRecipientUpsertCommandDTO toCommand(AlertRecipientUpsertRequestDTO request) {
        AlertRecipientUpsertRequestDTO body = request != null ? request : new AlertRecipientUpsertRequestDTO();
        return AlertRecipientUpsertCommandDTO.builder()
                .email(body.getEmail())
                .label(body.getLabel())
                .enabled(body.getEnabled())
                .build();
    }

    public AlertRecipientResponseDTO toResponse(AlertRecipientViewDTO view) {
        if (view == null) {
            return null;
        }
        return AlertRecipientResponseDTO.builder()
                .id(view.getId())
                .email(view.getEmail())
                .enabled(view.isEnabled())
                .label(view.getLabel())
                .createdAt(view.getCreatedAt())
                .updatedAt(view.getUpdatedAt())
                .build();
    }

    public List<AlertRecipientResponseDTO> toResponseList(List<AlertRecipientViewDTO> views) {
        return views == null ? List.of() : views.stream().map(this::toResponse).toList();
    }
}
