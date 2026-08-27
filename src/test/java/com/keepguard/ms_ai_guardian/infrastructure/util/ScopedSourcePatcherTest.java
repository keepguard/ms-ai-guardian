package com.keepguard.ms_ai_guardian.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedSourcePatcherTest {

    private static final String FILE = """
            package service

            func (s *SMSServiceImpl) ProcessBatchSMS() {
            	denom := 0
            	_ = 1000 / denom
            }

            func (s *SMSServiceImpl) ExecuteBugScenario(bugNumber int) error {
            	switch bugNumber {
            	case 1:
            		return fmt.Errorf("CODE_DEFECT_01: Divisao por zero")
            	case 2:
            		return fmt.Errorf("CODE_DEFECT_02: nil pointer")
            	}
            	return nil
            }
            """;

    @Test
    void extractsOnlyExecuteBugScenarioForCodeDefect01() {
        var slice = ScopedSourcePatcher.extract(FILE, "internal/core/service/sms_service.go",
                "CODE_DEFECT_01", "Divisao por zero no modulo de tarifacao");
        assertTrue(slice.functionSource().contains("ExecuteBugScenario"));
        assertFalse(slice.functionSource().contains("ProcessBatchSMS"));
    }

    @Test
    void splicesOnlyTheChangedFunction() {
        var slice = ScopedSourcePatcher.extract(FILE, "internal/core/service/sms_service.go",
                "CODE_DEFECT_01", "tarifacao");
        String llm = """
                func (s *SMSServiceImpl) ExecuteBugScenario(bugNumber int) error {
                	switch bugNumber {
                	case 1:
                		rate := 0
                		if rate <= 0 {
                			return nil
                		}
                		_ = 1000 / rate
                		return nil
                	case 2:
                		return fmt.Errorf("CODE_DEFECT_02: nil pointer")
                	}
                	return nil
                }
                """;
        String patched = ScopedSourcePatcher.applyReplacement(slice, llm);
        assertTrue(patched.contains("rate <= 0"));
        assertTrue(patched.contains("ProcessBatchSMS"));
        assertTrue(patched.contains("CODE_DEFECT_02"));
        assertTrue(patched.contains("1000 / denom"));
    }

    @Test
    void ignoresWholeFileRewriteFromLlm() {
        var slice = ScopedSourcePatcher.extract(FILE, "internal/core/service/sms_service.go",
                "CODE_DEFECT_01", "divisao");
        String llmFullFile = """
                package service

                func (s *SMSServiceImpl) ProcessBatchSMS() {
                	// LLM apagou os panics de laboratorio
                }

                func (s *SMSServiceImpl) ExecuteBugScenario(bugNumber int) error {
                	if bugNumber == 1 {
                		rate := 0
                		if rate <= 0 {
                			return nil
                		}
                	}
                	return nil
                }
                """;
        String patched = ScopedSourcePatcher.applyReplacement(slice, llmFullFile);
        assertTrue(patched.contains("1000 / denom"), "fluxo fora do incidente deve permanecer");
        assertTrue(patched.contains("rate <= 0"));
    }

    @Test
    void extractsFunctionContainingTheLoggedLine() {
        var slice = ScopedSourcePatcher.extract(FILE, "internal/core/service/sms_service.go",
                "erro genérico", "sem tokens da função", 4);
        assertTrue(slice.functionSource().contains("ProcessBatchSMS"));
        assertFalse(slice.functionSource().contains("ExecuteBugScenario"));
    }
}
