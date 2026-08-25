package com.keepguard.ms_ai_guardian.infrastructure.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Files;

@Slf4j
@Configuration
public class KubernetesClientConfig {

    @Value("${app.kubernetes.kubeconfig-path:}")
    private String kubeconfigPath;

    @Bean
    public KubernetesClient kubernetesClient() {
        try {
            // Se tiver caminho de kubeconfig customizado (ex: ambiente local com keepguard-kubeconfig.yaml)
            if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
                File file = new File(kubeconfigPath);
                if (file.exists()) {
                    log.info("Carregando KubernetesClient a partir do arquivo: {}", kubeconfigPath);
                    String kubeconfigContents = Files.readString(file.toPath());
                    Config config = Config.fromKubeconfig(kubeconfigContents);
                    return new KubernetesClientBuilder().withConfig(config).build();
                }
            }

            // Fallback padrão: In-Cluster Configuration (quando rodar dentro do K8s) ou padrão ~/.kube/config
            log.info("Carregando KubernetesClient padrão (In-Cluster ou auto-discovery)...");
            return new KubernetesClientBuilder().build();
        } catch (Exception e) {
            log.warn("Aviso ao inicializar KubernetesClient: {}. Criando cliente padrão com auto-discovery.", e.getMessage());
            return new KubernetesClientBuilder().build();
        }
    }
}
