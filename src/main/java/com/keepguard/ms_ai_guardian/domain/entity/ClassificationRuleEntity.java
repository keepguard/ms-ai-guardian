package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.ClassificationVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationRuleEntity {

    private UUID id;

    private String ruleKey;

    private int priority;

    private ClassificationVerdict verdict;

    private boolean requiresCodePr;

    private String errorContains;

    private String logsContains;

    private String summaryTemplate;

    private String explanationTemplate;

    private String suggestedActionTemplate;

    private boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
