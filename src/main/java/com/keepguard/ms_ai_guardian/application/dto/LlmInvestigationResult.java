package com.keepguard.ms_ai_guardian.application.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class LlmInvestigationResult {
    private String rootCause;
    private String summary;
    private String riskNotes;
    @Builder.Default
    private List<String> recommendedActionIds = new ArrayList<>();
    @Builder.Default
    private boolean heuristicFallback = false;
}
