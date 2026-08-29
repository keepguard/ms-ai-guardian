package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.ClassificationRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRuleEntity, UUID> {

    List<ClassificationRuleEntity> findByEnabledTrueOrderByPriorityAsc();

    boolean existsByRuleKey(String ruleKey);
}
