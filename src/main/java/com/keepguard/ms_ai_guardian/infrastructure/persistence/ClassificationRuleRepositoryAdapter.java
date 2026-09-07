package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.ClassificationRuleRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.ClassificationRuleEntity;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.ClassificationRuleJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.ClassificationRuleSpringRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ClassificationRuleRepositoryAdapter implements ClassificationRuleRepositoryPort {

    private final ClassificationRuleSpringRepository springRepository;
    private final ClassificationRuleJpaMapper mapper;

    @Override
    public ClassificationRuleEntity save(ClassificationRuleEntity domain) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(domain)));
    }

    @Override
    public List<ClassificationRuleEntity> findByEnabledTrueOrderByPriorityAsc() {
        return springRepository.findByEnabledTrueOrderByPriorityAsc().stream().map(mapper::toDomain).toList();
    }

    @Override
    public boolean existsByRuleKey(String ruleKey) {
        return springRepository.existsByRuleKey(ruleKey);
    }

    @Override
    public long count() {
        return springRepository.count();
    }
}
