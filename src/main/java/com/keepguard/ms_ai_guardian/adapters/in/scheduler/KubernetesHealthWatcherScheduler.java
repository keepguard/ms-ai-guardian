package com.keepguard.ms_ai_guardian.adapters.in.scheduler;

import com.keepguard.ms_ai_guardian.adapters.out.k8s.KubernetesInspectorService;
import com.keepguard.ms_ai_guardian.application.service.AiDiagnosticService;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KubernetesHealthWatcherScheduler {

    private final KubernetesInspectorService k8sInspector;
    private final AiDiagnosticService aiDiagnosticService;

    @Value("${app.guardian.namespace:keepguard}")
    private String targetNamespace;

    @Value("${app.guardian.watcher-enabled:true}")
    private boolean watcherEnabled;

    // Executa a cada 60 segundos buscando anomalias no cluster
    @Scheduled(fixedDelayString = "${app.guardian.scan-interval-ms:60000}", initialDelay = 15000)
    public void scanClusterHealth() {
        if (!watcherEnabled) {
            return;
        }

        try {
            List<Pod> unhealthyPods = k8sInspector.listUnhealthyPods(targetNamespace);
            if (unhealthyPods.isEmpty()) {
                log.debug("🛡️ [AI Guardian] Cluster {} saudável. Nenhum pod em estado anômalo.", targetNamespace);
                return;
            }

            log.warn("⚠️ [AI Guardian] Detectados {} pods com instabilidade no namespace {}. Iniciando diagnóstico...",
                    unhealthyPods.size(), targetNamespace);

            for (Pod pod : unhealthyPods) {
                String podName = pod.getMetadata().getName();
                String serviceName = pod.getMetadata().getLabels() != null && pod.getMetadata().getLabels().containsKey("app")
                        ? pod.getMetadata().getLabels().get("app")
                        : podName;

                String errorReason = "RESTART_OR_FAILURE_DETECTED";
                if (pod.getStatus().getPhase() != null) {
                    errorReason = pod.getStatus().getPhase();
                }

                aiDiagnosticService.diagnosePod(targetNamespace, podName, serviceName, errorReason, false);
            }

        } catch (Exception e) {
            log.error("Erro durante a varredura do cluster pelo AI Guardian: {}", e.getMessage(), e);
        }
    }
}
