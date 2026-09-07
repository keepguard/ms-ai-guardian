package com.keepguard.ms_ai_guardian.infrastructure.persistence.spring;

import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.ClassificationRuleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClassificationRuleSpringRepository extends JpaRepository<ClassificationRuleJpaEntity, UUID> {

    List<ClassificationRuleJpaEntity> findByEnabledTrueOrderByPriorityAsc();

    boolean existsByRuleKey(String ruleKey);
}
