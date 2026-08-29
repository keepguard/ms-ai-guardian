package com.keepguard.ms_ai_guardian.infrastructure.classification;

import com.keepguard.ms_ai_guardian.domain.classification.ClassificationRule;
import com.keepguard.ms_ai_guardian.domain.entity.ClassificationRuleEntity;
import com.keepguard.ms_ai_guardian.domain.enums.ClassificationVerdict;
import com.keepguard.ms_ai_guardian.domain.repository.ClassificationRuleRepository;
import com.keepguard.ms_ai_guardian.infrastructure.template.ClasspathResourceLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationCatalog {

    private final ClassificationRuleRepository repository;
    private final ClasspathResourceLoader classpath;

    public List<ClassificationRule> activeRules() {
        List<ClassificationRuleEntity> fromDb = repository.findByEnabledTrueOrderByPriorityAsc();
        if (!fromDb.isEmpty()) {
            return fromDb.stream().map(this::toDomain).toList();
        }
        return loadClasspath();
    }

    @SuppressWarnings("unchecked")
    public List<ClassificationRule> loadClasspath() {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(classpath.load("classification-rules.yml"));
        List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("rules");
        List<ClassificationRule> rules = new ArrayList<>();
        if (raw == null) {
            return rules;
        }
        for (Map<String, Object> item : raw) {
            rules.add(new ClassificationRule(
                    str(item.get("id")),
                    toInt(item.get("priority")),
                    ClassificationVerdict.valueOf(str(item.get("verdict"))),
                    Boolean.TRUE.equals(item.get("requiresCodePr")),
                    toStringList(item.get("errorContains")),
                    toStringList(item.get("logsContains")),
                    str(item.get("summaryTemplate")),
                    str(item.get("explanationTemplate")),
                    str(item.get("suggestedActionTemplate")),
                    item.get("enabled") == null || Boolean.TRUE.equals(item.get("enabled"))
            ));
        }
        return rules;
    }

    public void seedIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        for (ClassificationRule rule : loadClasspath()) {
            if (repository.existsByRuleKey(rule.id())) {
                continue;
            }
            repository.save(ClassificationRuleEntity.builder()
                    .ruleKey(rule.id())
                    .priority(rule.priority())
                    .verdict(rule.verdict())
                    .requiresCodePr(rule.requiresCodePr())
                    .errorContains(join(rule.errorContains()))
                    .logsContains(join(rule.logsContains()))
                    .summaryTemplate(rule.summaryTemplate())
                    .explanationTemplate(rule.explanationTemplate())
                    .suggestedActionTemplate(rule.suggestedActionTemplate())
                    .enabled(rule.enabled())
                    .build());
        }
        log.info("Catálogo de classificação semeado a partir do classpath ({} regras).", repository.count());
    }

    private ClassificationRule toDomain(ClassificationRuleEntity entity) {
        return new ClassificationRule(
                entity.getRuleKey(),
                entity.getPriority(),
                entity.getVerdict(),
                entity.isRequiresCodePr(),
                split(entity.getErrorContains()),
                split(entity.getLogsContains()),
                entity.getSummaryTemplate(),
                entity.getExplanationTemplate(),
                entity.getSuggestedActionTemplate(),
                entity.isEnabled()
        );
    }

    private static String join(List<String> values) {
        return values == null ? "" : String.join("\n", values);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\R"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).map(s -> s.toLowerCase(Locale.ROOT)).toList();
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
