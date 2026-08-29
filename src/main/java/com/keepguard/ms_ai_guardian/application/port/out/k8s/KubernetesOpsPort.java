package com.keepguard.ms_ai_guardian.application.port.out.k8s;

public interface KubernetesOpsPort {

    void rolloutRestart(String namespace, String deploymentName);
}
