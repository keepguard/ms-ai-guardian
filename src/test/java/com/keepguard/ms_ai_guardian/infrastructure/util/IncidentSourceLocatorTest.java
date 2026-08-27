package com.keepguard.ms_ai_guardian.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentSourceLocatorTest {

    @Test
    void parsesZapCaller() {
        String logs = "{\"caller\":\"service/sms_service.go:122\",\"msg\":\"Executando Cenário\"}";
        var hint = IncidentSourceLocator.parse(logs, "CODE_DEFECT_01");
        assertTrue(hint.basenames().contains("sms_service.go"));
        assertEquals(122, hint.lineNumber());
        assertEquals("sms_service.go:122", hint.fingerprintLocation());
    }

    @Test
    void parsesJavaStack() {
        String logs = "at com.keepguard.ms_auth.LoginService.authenticate(LoginService.java:88)";
        var hint = IncidentSourceLocator.parse(logs, "NullPointerException");
        assertTrue(hint.basenames().contains("LoginService.java"));
        assertEquals(88, hint.lineNumber());
    }

    @Test
    void ranksRepoPathByBasename() {
        var hint = IncidentSourceLocator.parse("sms_service.go:122", "divisao");
        List<String> ranked = IncidentSourceLocator.rankPaths(List.of(
                "cmd/main.go",
                "internal/core/service/sms_service.go",
                "internal/core/service/sms_service_test.go"
        ), hint, "CODE_DEFECT_01");
        assertEquals("internal/core/service/sms_service.go", ranked.get(0));
    }
}
