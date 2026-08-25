package com.keepguard.ms_ai_guardian.adapters.out.k8s;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KubernetesInspectorService {

    private final KubernetesClient k8sClient;

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

    public boolean isPodUnhealthy(Pod pod) {
        if (pod.getStatus() == null) return false;

        String phase = pod.getStatus().getPhase();
        if ("Failed".equalsIgnoreCase(phase) || "Unknown".equalsIgnoreCase(phase)) {
            return true;
        }

        // Verifica status dos containers
        if (pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                if (status.getRestartCount() != null && status.getRestartCount() > 0) {
                    // Se estiver com restart recente
                    return true;
                }
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
        return false;
    }

    public String getPodLogs(String namespace, String podName, int tailLines) {
        try {
            return k8sClient.pods().inNamespace(namespace).withName(podName)
                    .tailingLines(tailLines)
                    .getLog();
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
}
