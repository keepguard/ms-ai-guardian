package com.keepguard.ms_ai_guardian.infrastructure.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.File;
import java.nio.file.Files;

@Slf4j
@Configuration
public class KubernetesClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int REQUEST_TIMEOUT_MS = 15_000;

    @Value("${app.kubernetes.kubeconfig-path:}")
    private String kubeconfigPath;

    /**
     * Lazy: o Fabric8 não deve bloquear o boot do Spring. Timeouts curtos evitam
     * o cliente ficar minutos tentando descobrir a API sob CPU/memória apertada.
     */
    @Bean
    @Lazy
    public KubernetesClient kubernetesClient() {
        try {
            if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
                File file = new File(kubeconfigPath);
                if (file.exists()) {
                    log.info("Carregando KubernetesClient a partir do arquivo: {}", kubeconfigPath);
                    String kubeconfigContents = Files.readString(file.toPath());
                    Config config = Config.fromKubeconfig(kubeconfigContents);
                    applyTimeouts(config);
                    return new KubernetesClientBuilder().withConfig(config).build();
                }
            }

            log.info("Carregando KubernetesClient padrão (In-Cluster ou auto-discovery)...");
            Config config = Config.autoConfigure(null);
            applyTimeouts(config);
            return new KubernetesClientBuilder().withConfig(config).build();
        } catch (Exception e) {
            log.warn("Aviso ao inicializar KubernetesClient: {}. Criando cliente padrão com auto-discovery.", e.getMessage());
            Config fallback = Config.autoConfigure(null);
            applyTimeouts(fallback);
            return new KubernetesClientBuilder().withConfig(fallback).build();
        }
    }

    private static void applyTimeouts(Config config) {
        if (config == null) {
            return;
        }
        config.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        config.setRequestTimeout(REQUEST_TIMEOUT_MS);
        config.setScaleTimeout(15_000L);
    }
}
