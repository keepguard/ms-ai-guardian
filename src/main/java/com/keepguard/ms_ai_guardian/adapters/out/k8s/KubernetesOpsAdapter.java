package com.keepguard.ms_ai_guardian.adapters.out.k8s;

import com.keepguard.ms_ai_guardian.application.port.out.k8s.KubernetesOpsPort;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KubernetesOpsAdapter implements KubernetesOpsPort {

    private final KubernetesClient k8sClient;

    public KubernetesOpsAdapter(@Lazy KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public void rolloutRestart(String namespace, String deploymentName) {
        log.info("Rollout restart {}/{}", namespace, deploymentName);
        k8sClient.apps().deployments().inNamespace(namespace).withName(deploymentName).rolling().restart();
    }
}
