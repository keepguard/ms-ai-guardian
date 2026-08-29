package com.keepguard.ms_ai_guardian.application.dto;

import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ClusterFacts {
    private String namespace;
    private String serviceName;
    private String podName;
    private String deploymentName;
    private Integer desiredReplicas;
    private Integer availableReplicas;
    private Integer readyReplicas;
    private Integer replicaSetCount;
    private String phase;
    private String waitingReason;
    private String terminatedReason;
    private Integer restartCount;
    private Integer exitCode;
    private boolean replicasIntentionallyZero;
    private boolean crashLoop;
    private boolean imagePullFailure;
    @Builder.Default
    private List<String> warningEvents = new ArrayList<>();
    private String logsSnippet;
    private String describe;
    private K8sConclusion conclusion;
    private boolean healthy;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("namespace", namespace);
        map.put("serviceName", serviceName);
        map.put("podName", podName);
        map.put("deploymentName", deploymentName);
        map.put("desiredReplicas", desiredReplicas);
        map.put("availableReplicas", availableReplicas);
        map.put("readyReplicas", readyReplicas);
        map.put("replicaSetCount", replicaSetCount);
        map.put("phase", phase);
        map.put("waitingReason", waitingReason);
        map.put("terminatedReason", terminatedReason);
        map.put("restartCount", restartCount);
        map.put("exitCode", exitCode);
        map.put("replicasIntentionallyZero", replicasIntentionallyZero);
        map.put("crashLoop", crashLoop);
        map.put("imagePullFailure", imagePullFailure);
        map.put("warningEvents", warningEvents);
        map.put("logsSnippet", logsSnippet);
        map.put("describe", describe);
        map.put("conclusion", conclusion != null ? conclusion.name() : null);
        map.put("healthy", healthy);
        return map;
    }
}
