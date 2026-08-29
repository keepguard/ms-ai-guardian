package com.keepguard.ms_ai_guardian.adapters.out.k8s;

import com.keepguard.ms_ai_guardian.application.dto.ClusterFacts;
import com.keepguard.ms_ai_guardian.domain.enums.K8sConclusion;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KubernetesInspectorService {

    private static final Set<String> SKIP_LOG_SCAN_APPS = Set.of(
            "postgres", "redis", "rabbitmq", "ollama", "prometheus", "grafana",
            "minio", "ms-ai-guardian"
    );

    private final KubernetesClient k8sClient;

    public KubernetesInspectorService(@Lazy KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    public List<Pod> listUnhealthyPods(String namespace) {
        try {
            return k8sClient.pods().inNamespace(namespace).list().getItems().stream()
                    .filter(this::isPodUnhealthy)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Erro ao listar pods no namespace {}: {}", namespace, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<io.fabric8.kubernetes.api.model.apps.Deployment> listDeploymentsWithZeroReplicas(String namespace) {
        try {
            return k8sClient.apps().deployments().inNamespace(namespace).list().getItems().stream()
                    .filter(d -> {
                        Integer desired = d.getSpec() != null ? d.getSpec().getReplicas() : 1;
                        Integer available = d.getStatus() != null && d.getStatus().getAvailableReplicas() != null
                                ? d.getStatus().getAvailableReplicas() : 0;
                        // Alerta se o deployment deveria ter réplicas mas tem 0 disponíveis, ou se foi explicitamente zerado
                        return (desired != null && desired > 0 && available == 0) || (desired != null && desired == 0);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Erro ao verificar deployments zerados no namespace {}: {}", namespace, e.getMessage());
            return Collections.emptyList();
        }
    }

    public boolean isPodUnhealthy(Pod pod) {
        if (pod.getStatus() == null) return false;

        String phase = pod.getStatus().getPhase();
        if ("Succeeded".equalsIgnoreCase(phase)) {
            return false;
        }

        if ("Failed".equalsIgnoreCase(phase) || "Unknown".equalsIgnoreCase(phase)) {
            log.info("⚠️ Pod {} marcado como anômalo por Phase: {}", pod.getMetadata().getName(), phase);
            return true;
        }

        // Verifica status dos containers (apenas estados de erro ativo)
        if (pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                if (status.getState() != null) {
                    if (status.getState().getWaiting() != null) {
                        String reason = status.getState().getWaiting().getReason();
                        if ("CrashLoopBackOff".equalsIgnoreCase(reason) ||
                            "ImagePullBackOff".equalsIgnoreCase(reason) ||
                            "ErrImagePull".equalsIgnoreCase(reason) ||
                            "CreateContainerConfigError".equalsIgnoreCase(reason)) {
                            return true;
                        }
                    }
                    if (status.getState().getTerminated() != null) {
                        Integer exitCode = status.getState().getTerminated().getExitCode();
                        if (exitCode != null && exitCode != 0) {
                            return true;
                        }
                    }
                }
            }
        }

        // Inspeciona logs de apps (não de infra) em busca de PANIC / CODE_DEFECT / NPE
        if ("Running".equalsIgnoreCase(phase) && shouldScanApplicationLogs(pod)) {
            String logs = getPodLogs(pod.getMetadata().getNamespace(), pod.getMetadata().getName(), 80);
            if (logs.contains("PANIC RECOVER") || logs.contains("NullPointerException") 
                    || logs.contains("BadSqlGrammarException") || logs.contains("CODE_DEFECT_")
                    || logs.contains("PANIC_RUNTIME")) {
                log.info("🔥 Pod {} marcado como anômalo por detecção de padrão crítico nos logs!", pod.getMetadata().getName());
                return true;
            }
        }

        return false;
    }

    private boolean shouldScanApplicationLogs(Pod pod) {
        String name = pod.getMetadata() != null ? pod.getMetadata().getName() : "";
        if (name.startsWith("ms-ai-guardian")) {
            return false;
        }
        String app = pod.getMetadata() != null && pod.getMetadata().getLabels() != null
                ? pod.getMetadata().getLabels().getOrDefault("app", "")
                : "";
        return app.isBlank() || !SKIP_LOG_SCAN_APPS.contains(app);
    }

    public String getPodLogs(String namespace, String podName, int tailLines) {
        try {
            String logs = k8sClient.pods().inNamespace(namespace).withName(podName)
                    .tailingLines(tailLines)
                    .getLog();
            return com.keepguard.ms_ai_guardian.infrastructure.util.LlmContextLimiter.tail(logs, 4000);
        } catch (Exception e) {
            log.warn("Não foi possível obter logs do pod {}/{}: {}", namespace, podName, e.getMessage());
            return "Logs não disponíveis: " + e.getMessage();
        }
    }

    public List<String> getRecentWarningEvents(String namespace, String podName) {
        try {
            return k8sClient.v1().events().inNamespace(namespace).list().getItems().stream()
                    .filter(ev -> "Warning".equalsIgnoreCase(ev.getType()))
                    .filter(ev -> podName == null || (ev.getInvolvedObject() != null && podName.equals(ev.getInvolvedObject().getName())))
                    .map(ev -> String.format("[%s] %s: %s (Count: %d)",
                            ev.getLastTimestamp() != null ? ev.getLastTimestamp() : "Agora",
                            ev.getReason(),
                            ev.getMessage(),
                            ev.getCount() != null ? ev.getCount() : 1))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Erro ao buscar eventos do pod {}/{}: {}", namespace, podName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public String describePodHealth(String namespace, String podName) {
        try {
            Optional<Pod> podOpt = Optional.ofNullable(k8sClient.pods().inNamespace(namespace).withName(podName).get());
            if (podOpt.isEmpty()) {
                return "Pod " + podName + " não encontrado no namespace " + namespace;
            }

            Pod pod = podOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("Pod: ").append(pod.getMetadata().getName()).append("\n");
            sb.append("Namespace: ").append(namespace).append("\n");
            sb.append("Status Phase: ").append(pod.getStatus().getPhase()).append("\n");

            if (pod.getStatus().getContainerStatuses() != null) {
                for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                    sb.append("Container: ").append(cs.getName()).append("\n");
                    sb.append("  Ready: ").append(cs.getReady()).append("\n");
                    sb.append("  Restart Count: ").append(cs.getRestartCount()).append("\n");
                    if (cs.getState() != null && cs.getState().getWaiting() != null) {
                        sb.append("  Waiting Reason: ").append(cs.getState().getWaiting().getReason()).append("\n");
                        sb.append("  Waiting Message: ").append(cs.getState().getWaiting().getMessage()).append("\n");
                    }
                    if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                        sb.append("  Last Termination Exit Code: ").append(cs.getLastState().getTerminated().getExitCode()).append("\n");
                        sb.append("  Last Termination Reason: ").append(cs.getLastState().getTerminated().getReason()).append("\n");
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Erro ao inspecionar pod: " + e.getMessage();
        }
    }

    public ClusterFacts collectFacts(String namespace, String podName, String serviceName) {
        Deployment deployment = findDeployment(namespace, serviceName);
        String deploymentName = deployment != null ? deployment.getMetadata().getName() : serviceName;
        Integer desired = 1;
        Integer available = 0;
        Integer ready = 0;
        boolean replicasZero = false;
        int replicaSetCount = 0;
        if (deployment != null) {
            desired = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 1;
            available = deployment.getStatus() != null && deployment.getStatus().getAvailableReplicas() != null
                    ? deployment.getStatus().getAvailableReplicas() : 0;
            ready = deployment.getStatus() != null && deployment.getStatus().getReadyReplicas() != null
                    ? deployment.getStatus().getReadyReplicas() : 0;
            replicasZero = desired == 0;
            replicaSetCount = countReplicaSets(namespace, deploymentName);
        }

        Pod pod = resolvePod(namespace, podName, serviceName);
        String resolvedPodName = pod != null && pod.getMetadata() != null ? pod.getMetadata().getName() : podName;
        String phase = pod != null && pod.getStatus() != null ? pod.getStatus().getPhase() : null;
        String waiting = null;
        String terminated = null;
        Integer restartCount = 0;
        Integer exitCode = null;
        boolean crashLoop = false;
        boolean imagePull = false;
        if (pod != null && pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                if (cs.getRestartCount() != null) {
                    restartCount = Math.max(restartCount, cs.getRestartCount());
                }
                if (cs.getState() != null && cs.getState().getWaiting() != null) {
                    waiting = cs.getState().getWaiting().getReason();
                    if ("CrashLoopBackOff".equalsIgnoreCase(waiting)) {
                        crashLoop = true;
                    }
                    if ("ImagePullBackOff".equalsIgnoreCase(waiting) || "ErrImagePull".equalsIgnoreCase(waiting)
                            || "CreateContainerConfigError".equalsIgnoreCase(waiting)) {
                        imagePull = true;
                    }
                }
                if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                    terminated = cs.getLastState().getTerminated().getReason();
                    exitCode = cs.getLastState().getTerminated().getExitCode();
                    if ("OOMKilled".equalsIgnoreCase(terminated)) {
                        crashLoop = true;
                    }
                }
            }
        }

        List<String> events = getRecentWarningEvents(namespace, resolvedPodName);
        String describe = resolvedPodName != null ? describePodHealth(namespace, resolvedPodName) : "Pod não encontrado";
        String logs = resolvedPodName != null ? getPodLogs(namespace, resolvedPodName, 80) : "";

        K8sConclusion conclusion = classify(deployment == null, replicasZero, crashLoop, imagePull, phase, waiting,
                events, desired, available);
        boolean hasUnhealthy = listUnhealthyPodsForService(namespace, serviceName);
        boolean healthy = desired != null && desired > 0
                && available != null && available > 0
                && !crashLoop
                && !imagePull
                && !hasUnhealthy;

        return ClusterFacts.builder()
                .namespace(namespace)
                .serviceName(serviceName)
                .podName(resolvedPodName)
                .deploymentName(deploymentName)
                .desiredReplicas(desired)
                .availableReplicas(available)
                .readyReplicas(ready)
                .replicaSetCount(replicaSetCount)
                .phase(phase)
                .waitingReason(waiting)
                .terminatedReason(terminated)
                .restartCount(restartCount)
                .exitCode(exitCode)
                .replicasIntentionallyZero(replicasZero)
                .crashLoop(crashLoop)
                .imagePullFailure(imagePull)
                .warningEvents(events)
                .logsSnippet(logs)
                .describe(describe)
                .conclusion(conclusion)
                .healthy(healthy)
                .build();
    }

    public boolean isServiceHealthy(String namespace, String serviceName) {
        try {
            return collectFacts(namespace, null, serviceName).isHealthy();
        } catch (Exception e) {
            log.warn("Falha ao avaliar saúde de {}/{}: {}", namespace, serviceName, e.getMessage());
            return false;
        }
    }

    public void deletePod(String namespace, String podName) {
        k8sClient.pods().inNamespace(namespace).withName(podName).delete();
    }

    public void rolloutRestart(String namespace, String deploymentName) {
        k8sClient.apps().deployments().inNamespace(namespace).withName(deploymentName).rolling().restart();
    }

    public void rollbackRevision(String namespace, String deploymentName) {
        k8sClient.apps().deployments().inNamespace(namespace).withName(deploymentName).rolling().undo();
    }

    public void scaleDeployment(String namespace, String deploymentName, int replicas) {
        k8sClient.apps().deployments().inNamespace(namespace).withName(deploymentName).scale(replicas);
    }

    private boolean listUnhealthyPodsForService(String namespace, String serviceName) {
        try {
            return k8sClient.pods().inNamespace(namespace).withLabel("app", serviceName).list().getItems().stream()
                    .anyMatch(this::isPodUnhealthy);
        } catch (Exception e) {
            return false;
        }
    }

    private Deployment findDeployment(String namespace, String serviceName) {
        try {
            Deployment byName = k8sClient.apps().deployments().inNamespace(namespace).withName(serviceName).get();
            if (byName != null) {
                return byName;
            }
            List<Deployment> labeled = k8sClient.apps().deployments().inNamespace(namespace)
                    .withLabel("app", serviceName).list().getItems();
            return labeled.isEmpty() ? null : labeled.get(0);
        } catch (Exception e) {
            log.warn("Deployment {}/{} não encontrado: {}", namespace, serviceName, e.getMessage());
            return null;
        }
    }

    private int countReplicaSets(String namespace, String deploymentName) {
        try {
            List<ReplicaSet> items = k8sClient.apps().replicaSets().inNamespace(namespace).list().getItems();
            return (int) items.stream()
                    .filter(rs -> rs.getMetadata() != null && rs.getMetadata().getOwnerReferences() != null)
                    .filter(rs -> rs.getMetadata().getOwnerReferences().stream()
                            .anyMatch(ow -> deploymentName.equals(ow.getName())))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private Pod resolvePod(String namespace, String podName, String serviceName) {
        try {
            if (podName != null && !podName.isBlank() && !podName.endsWith("-deployment")) {
                Pod exact = k8sClient.pods().inNamespace(namespace).withName(podName).get();
                if (exact != null) {
                    return exact;
                }
            }
            List<Pod> labeled = k8sClient.pods().inNamespace(namespace).withLabel("app", serviceName).list().getItems();
            return labeled.isEmpty() ? null : labeled.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private K8sConclusion classify(boolean noController, boolean replicasZero, boolean crashLoop, boolean imagePull,
            String phase, String waiting, List<String> events, Integer desired, Integer available) {
        String eventBlob = events == null ? "" : String.join(" ", events);
        if (noController) {
            return K8sConclusion.NO_CONTROLLER;
        }
        if (replicasZero) {
            return K8sConclusion.REPLICAS_INTENTIONALLY_ZERO;
        }
        if (imagePull || "ImagePullBackOff".equalsIgnoreCase(waiting) || "ErrImagePull".equalsIgnoreCase(waiting)
                || "CreateContainerConfigError".equalsIgnoreCase(waiting)) {
            return K8sConclusion.IMAGE_OR_CONFIG;
        }
        if ("Pending".equalsIgnoreCase(phase) || eventBlob.contains("FailedScheduling")) {
            return K8sConclusion.UNSCHEDULABLE;
        }
        if ("Unknown".equalsIgnoreCase(phase) || eventBlob.contains("Evicted") || eventBlob.contains("NodeNotReady")) {
            return K8sConclusion.NODE_FAILURE;
        }
        if (crashLoop || "CrashLoopBackOff".equalsIgnoreCase(waiting)) {
            return K8sConclusion.CONTROLLER_ALREADY_RETRYING;
        }
        if (desired != null && desired > 0 && (available == null || available == 0)) {
            return K8sConclusion.TRANSIENT_INFRA_RECOVERABLE;
        }
        return K8sConclusion.CONTROLLER_ALREADY_RETRYING;
    }
}
