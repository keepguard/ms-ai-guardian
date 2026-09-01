package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.application.dto.ClusterStormAssessment;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterStormLogicTest {

    @Test
    void stormActiveWhenNodeNotReady() {
        var assessment = new ClusterStormAssessment(
                true, 10, 2, List.of("ms-auth"), true, "NODE_NOT_READY");
        assertTrue(assessment.stormActive());
    }

    @Test
    void stormActiveWhenMassOutage() {
        int total = 30;
        int unavailable = 26;
        int percent = (unavailable * 100) / total;
        var storm = stormConfig();
        boolean massOutage = unavailable >= storm.getMinAffectedDeployments()
                && percent >= storm.getDeploymentThresholdPercent();

        assertTrue(massOutage);
        var assessment = new ClusterStormAssessment(
                false, total, unavailable, List.of("a", "b"), massOutage, "MASS_DEPLOYMENT_UNAVAILABLE");
        assertTrue(assessment.stormActive());
    }

    @Test
    void noStormWhenFewDeploymentsDown() {
        var storm = stormConfig();
        int total = 30;
        int unavailable = 3;
        int percent = (unavailable * 100) / total;
        boolean massOutage = unavailable >= storm.getMinAffectedDeployments()
                && percent >= storm.getDeploymentThresholdPercent();

        assertFalse(massOutage);
        var assessment = new ClusterStormAssessment(
                false, total, unavailable, List.of("ms-auth"), false, "NONE");
        assertFalse(assessment.stormActive());
    }

    @Test
    void unavailablePercentCalculation() {
        var assessment = new ClusterStormAssessment(
                false, 25, 10, List.of(), true, "MASS_DEPLOYMENT_UNAVAILABLE");
        assertTrue(assessment.unavailablePercent() >= 40);
    }

    @Test
    void serviceListSampleFormatting() {
        List<String> services = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "svc-" + i)
                .toList();
        String sample = services.size() > 8
                ? String.join(", ", services.subList(0, 8)) + "… (+" + (services.size() - 8) + ")"
                : String.join(", ", services);
        assertTrue(sample.contains("svc-8"));
        assertTrue(sample.contains("+4"));
    }

    private static GuardianProperties.Storm stormConfig() {
        GuardianProperties.Storm storm = new GuardianProperties.Storm();
        storm.setDeploymentThresholdPercent(40);
        storm.setMinAffectedDeployments(5);
        storm.setInfraAlertConfirmScans(2);
        return storm;
    }
}
