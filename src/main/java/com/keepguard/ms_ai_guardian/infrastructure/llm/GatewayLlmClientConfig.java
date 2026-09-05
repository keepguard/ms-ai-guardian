package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@ConditionalOnExpression("'${app.guardian.llm.provider:none}'.equalsIgnoreCase('gateway')")
public class GatewayLlmClientConfig {

    @Bean(name = "llmGatewayRestClient")
    RestClient llmGatewayRestClient(RestClient.Builder builder, GuardianLlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        int readSeconds = Math.max(props.getTimeoutSeconds(), props.getCodegenTimeoutSeconds()) + 5;
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));
        String baseUrl = props.getGatewayUrl() == null ? "" : props.getGatewayUrl().trim();
        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
