package com.keepguard.ms_ai_guardian.application.dto;

import java.util.List;

public record ClusterStormAssessment(
        boolean nodeNotReady,
        int totalDeployments,
        int unavailableDeployments,
        List<String> unavailableServiceNames,
        boolean stormActive,
        String stormReason) {

    public int unavailablePercent() {
        if (totalDeployments <= 0) {
            return 0;
        }
        return (unavailableDeployments * 100) / totalDeployments;
    }
}
