package com.keepguard.ms_ai_guardian.adapters.in.rest.diagnostic.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualDiagnoseRequestDTO {
    private String namespace;
    private String podName;
    private String serviceName;
    private String errorReason;
    private boolean forceSendEmail;
}
