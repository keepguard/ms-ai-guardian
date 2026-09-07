package com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient;

import com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.dto.request.AlertRecipientUpsertRequestDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.dto.response.AlertRecipientResponseDTO;
import com.keepguard.ms_ai_guardian.adapters.in.rest.alertrecipient.mapper.AlertRecipientAdapterMapper;
import com.keepguard.ms_ai_guardian.application.port.in.AlertRecipientPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/guardian")
@RequiredArgsConstructor
public class AlertRecipientController {

    private final AlertRecipientPort alertRecipientPort;
    private final AlertRecipientAdapterMapper mapper;

    @GetMapping("/alert-recipients")
    public ResponseEntity<List<AlertRecipientResponseDTO>> listRecipients() {
        return ResponseEntity.ok(mapper.toResponseList(alertRecipientPort.list()));
    }

    @PutMapping("/alert-recipients")
    public ResponseEntity<AlertRecipientResponseDTO> upsertRecipient(
            @RequestBody AlertRecipientUpsertRequestDTO request) {
        return ResponseEntity.ok(mapper.toResponse(alertRecipientPort.upsert(mapper.toCommand(request))));
    }

    @PatchMapping("/alert-recipients/{id}")
    public ResponseEntity<AlertRecipientResponseDTO> patchRecipient(
            @PathVariable UUID id,
            @RequestBody AlertRecipientUpsertRequestDTO request) {
        return ResponseEntity.ok(mapper.toResponse(alertRecipientPort.patch(id, mapper.toCommand(request))));
    }
}
