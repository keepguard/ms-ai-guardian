package com.keepguard.ms_ai_guardian.domain.classification;

import com.keepguard.ms_ai_guardian.domain.enums.ClassificationVerdict;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassificationEngineTest {

    @Test
    void tenantNotFoundIsDataInconsistencyWithoutPr() {
        BusinessVerdict verdict = ClassificationEngine.evaluate(
                classpathRules(), "auth failed", "empresa/tenant não encontrado para a api key");
        assertEquals(ClassificationVerdict.DATA_INCONSISTENCY, verdict.type());
        assertFalse(verdict.requiresCodePr());
    }

    @Test
    void imagePullIsInfrastructure() {
        BusinessVerdict verdict = ClassificationEngine.evaluate(
                classpathRules(), "ImagePullBackOff", "Failed to pull and unpack image");
        assertEquals(ClassificationVerdict.INFRASTRUCTURE_FAULT, verdict.type());
        assertFalse(verdict.requiresCodePr());
    }

    @Test
    void panicIsCodeDefectWithPr() {
        BusinessVerdict verdict = ClassificationEngine.evaluate(
                classpathRules(), "PANIC_RUNTIME_EXCEPTION", "panic recover in handler");
        assertEquals(ClassificationVerdict.CODE_DEFECT, verdict.type());
        assertTrue(verdict.requiresCodePr());
    }

    @Test
    void unmatchedFallsBackToCodeDefect() {
        BusinessVerdict verdict = ClassificationEngine.evaluate(
                classpathRules(), "something-new", "log sem sinal conhecido");
        assertEquals(ClassificationVerdict.CODE_DEFECT, verdict.type());
        assertTrue(verdict.requiresCodePr());
    }

    @SuppressWarnings("unchecked")
    private static List<ClassificationRule> classpathRules() {
        Yaml yaml = new Yaml();
        InputStream in = ClassificationEngineTest.class.getClassLoader()
                .getResourceAsStream("classification-rules.yml");
        Map<String, Object> root = yaml.load(in);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("rules");
        List<ClassificationRule> rules = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            rules.add(new ClassificationRule(
                    String.valueOf(item.get("id")),
                    ((Number) item.get("priority")).intValue(),
                    ClassificationVerdict.valueOf(String.valueOf(item.get("verdict"))),
                    Boolean.TRUE.equals(item.get("requiresCodePr")),
                    toList(item.get("errorContains")),
                    toList(item.get("logsContains")),
                    String.valueOf(item.get("summaryTemplate")),
                    String.valueOf(item.get("explanationTemplate")),
                    String.valueOf(item.get("suggestedActionTemplate")),
                    item.get("enabled") == null || Boolean.TRUE.equals(item.get("enabled"))
            ));
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).map(s -> s.toLowerCase(Locale.ROOT)).toList();
        }
        return List.of();
    }
}
