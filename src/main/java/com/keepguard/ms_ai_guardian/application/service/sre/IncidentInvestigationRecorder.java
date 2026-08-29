package com.keepguard.ms_ai_guardian.application.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.application.dto.LlmInvestigationResult;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentActionSuggestion;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentEvidence;
import com.keepguard.ms_ai_guardian.domain.enums.InvestigationSource;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentActionSuggestionRepository;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentInvestigationRecorder {

    private final IncidentEvidenceRepository evidenceRepository;
    private final IncidentActionSuggestionRepository suggestionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void persistInvestigation(Incident incident, ClusterFacts facts, LlmInvestigationResult llm) {
        try {
            evidenceRepository.save(IncidentEvidence.builder()
                    .incidentId(incident.getId())
                    .kind("CLUSTER_FACTS")
                    .payloadJson(objectMapper.writeValueAsString(facts.toMap()))
                    .build());
        } catch (Exception e) {
            log.warn("Falha ao gravar evidência: {}", e.getMessage());
        }

        incident.setK8sConclusion(facts.getConclusion() != null ? facts.getConclusion().name() : null);
        incident.setInvestigationSource(llm.isHeuristicFallback()
                ? InvestigationSource.HEURISTIC_FALLBACK
                : InvestigationSource.LLM);
        incident.setAiRootCauseAnalysis(llm.getRootCause());
        incident.setAiSummary(llm.getSummary());
        incident.setAiRecommendedAction(llm.getRiskNotes());

        suggestionRepository.deleteByIncidentId(incident.getId());
        for (ActionCatalogPolicy.SuggestionDraft draft : ActionCatalogPolicy.build(facts, llm)) {
            suggestionRepository.save(IncidentActionSuggestion.builder()
                    .incidentId(incident.getId())
                    .actionType(draft.actionType())
                    .label(draft.label())
                    .risk(draft.risk())
                    .enabled(draft.enabled())
                    .disabledReason(draft.disabledReason())
                    .aiRationale(draft.aiRationale())
                    .payloadJson(draft.payloadJson())
                    .build());
        }
    }
}
