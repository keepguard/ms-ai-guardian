package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.ClassificationRuleEntity;

import java.util.List;

public interface ClassificationRuleRepositoryPort {
    ClassificationRuleEntity save(ClassificationRuleEntity rule);

    List<ClassificationRuleEntity> findByEnabledTrueOrderByPriorityAsc();

    boolean existsByRuleKey(String ruleKey);

    long count();
}
