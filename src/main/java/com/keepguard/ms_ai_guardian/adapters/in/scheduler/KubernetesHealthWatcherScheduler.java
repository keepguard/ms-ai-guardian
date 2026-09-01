package com.keepguard.ms_ai_guardian.adapters.in.scheduler;

import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.port.in.HandlePrEventPort;
import com.keepguard.ms_ai_guardian.application.service.AiDiagnosticService;
import com.keepguard.ms_ai_guardian.application.service.sre.ClusterStormService;
import com.keepguard.ms_ai_guardian.application.service.sre.IncidentReconciliationService;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KubernetesHealthWatcherScheduler {

    private final KubernetesInspectorService k8sInspector;
    private final AiDiagnosticService aiDiagnosticService;
    private final IncidentReconciliationService incidentReconciliationService;
    private final ClusterStormService clusterStormService;
    private final HandlePrEventPort handlePrEvent;
    private final GuardianProperties properties;

    @Scheduled(fixedDelay = 45000, initialDelay = 10000)
    public void scanPullRequestInteractions() {
        try {
            handlePrEvent.scanOpenPullRequests();
        } catch (Exception e) {
            log.error("Erro ao verificar interações do GitHub: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.guardian.scan-interval-ms:60000}", initialDelay = 30000)
    public void scanClusterHealth() {
        if (!properties.isWatcherEnabled()) {
            return;
        }
        String targetNamespace = properties.getNamespace();
        try {
            if (clusterStormService.handleWatcherScan(targetNamespace)) {
                log.info("[AI Guardian Watcher] Modo tempestade ativo em {} — diagnósticos individuais suprimidos.",
                        targetNamespace);
                incidentReconciliationService.reconcileOpenIncidents();
                return;
            }

            List<Pod> unhealthyPods = k8sInspector.listUnhealthyPods(targetNamespace);
            log.info("[AI Guardian Watcher] Namespace {}. Pods anômalos: {}", targetNamespace, unhealthyPods.size());
            for (Pod pod : unhealthyPods) {
                String podName = pod.getMetadata().getName();
                String serviceName = pod.getMetadata().getLabels() != null && pod.getMetadata().getLabels().containsKey("app")
                        ? pod.getMetadata().getLabels().get("app")
                        : podName;

                String errorReason = "RESTART_OR_FAILURE_DETECTED";
                if (pod.getStatus().getPhase() != null) {
                    errorReason = pod.getStatus().getPhase();
                }
                String logs = k8sInspector.getPodLogs(targetNamespace, podName, 80);
                if (logs.contains("CODE_DEFECT_")) {
                    int idx = logs.indexOf("CODE_DEFECT_");
                    int end = logs.indexOf("\n", idx);
                    errorReason = end != -1 ? logs.substring(idx, end).trim() : logs.substring(idx, Math.min(idx + 50, logs.length()));
                } else if (logs.contains("PANIC RECOVER") || logs.contains("PANIC_RUNTIME")) {
                    errorReason = "PANIC_RUNTIME_EXCEPTION";
                } else if (logs.contains("NullPointerException")) {
                    errorReason = "NullPointerException";
                }
                aiDiagnosticService.diagnosePod(targetNamespace, podName, serviceName, errorReason, false);
            }

            List<io.fabric8.kubernetes.api.model.apps.Deployment> zeroDeployments =
                    k8sInspector.listDeploymentsWithZeroReplicas(targetNamespace);
            for (var dep : zeroDeployments) {
                String depName = dep.getMetadata().getName();
                aiDiagnosticService.diagnosePod(targetNamespace, depName + "-deployment", depName,
                        "SERVICE_OUTAGE_ZERO_REPLICAS_AVAILABLE", false);
            }

            incidentReconciliationService.reconcileOpenIncidents();
        } catch (Exception e) {
            log.error("Erro durante a varredura do cluster pelo AI Guardian: {}", e.getMessage(), e);
        }
    }
}
