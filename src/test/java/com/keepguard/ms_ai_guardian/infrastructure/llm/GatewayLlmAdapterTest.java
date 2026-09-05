package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import com.keepguard.ms_ai_guardian.domain.repository.LlmInvocationRepository;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import com.keepguard.ms_ai_guardian.infrastructure.oauth.OAuthClientCredentialsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class GatewayLlmAdapterTest {

    private static final String GATEWAY = "http://llm.test";

    @Mock
    private GuardianLlmProperties llmProperties;
    @Mock
    private GuardianProperties guardianProperties;
    @Mock
    private LlmInvocationRepository invocationRepository;
    @Mock
    private OAuthClientCredentialsClient oauthClient;

    private MockRestServiceServer server;
    private GatewayLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        RestClient restClient = RestClient.create(restTemplate);
        lenient().when(llmProperties.getGatewayUrl()).thenReturn(GATEWAY);
        lenient().when(llmProperties.isEnabled()).thenReturn(true);
        lenient().when(oauthClient.getToken(any())).thenReturn(Optional.empty());
        adapter = new GatewayLlmAdapter(llmProperties, guardianProperties, invocationRepository, oauthClient, restClient);
    }

    @Test
    void availableWhenGatewayEnabledAndUrlSet() {
        assertTrue(adapter.available());
    }

    @Test
    void unavailableWhenDisabledRecordsFallbackWithoutHttp() {
        when(llmProperties.isEnabled()).thenReturn(false);
        assertFalse(adapter.available());

        Optional<String> result = adapter.complete(
                LlmPort.LlmRequest.of("prompt", 5, PromptKeys.SRE_INVESTIGATE));

        assertTrue(result.isEmpty());
        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(invocationRepository).save(captor.capture());
        assertTrue(captor.getValue().isFallbackUsed());
    }

    @Test
    void completePostsToGatewayAndReturnsContent() {
        when(llmProperties.getMaxTokens()).thenReturn(256);
        when(llmProperties.getTemperature()).thenReturn(0.2);
        when(guardianProperties.getTenantId()).thenReturn("company-1");
        when(invocationRepository.save(any(LlmInvocation.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID incidentId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        server.expect(requestTo(GATEWAY + "/api/v1/llm/complete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Company-Id", "company-1"))
                .andExpect(header("X-Correlation-ID", incidentId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sourceService").value("ms-ai-guardian"))
                .andExpect(jsonPath("$.feature").value(PromptKeys.SRE_INVESTIGATE))
                .andExpect(jsonPath("$.companyId").value("company-1"))
                .andExpect(jsonPath("$.maxTokens").value(256))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("diagnóstico")))
                .andRespond(withSuccess("""
                        {
                          "content": "causa raiz em português",
                          "model": "gpt-4.1-mini",
                          "providerType": "openai",
                          "usage": {"promptTokens": 11, "completionTokens": 4, "totalTokens": 15}
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.complete(new LlmPort.LlmRequest(
                "diagnóstico do pod", 10, PromptKeys.SRE_INVESTIGATE, "classpath", incidentId));

        server.verify();
        assertEquals("causa raiz em português", result.orElseThrow());
        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(invocationRepository).save(captor.capture());
        assertFalse(captor.getValue().isFallbackUsed());
        assertEquals("gpt-4.1-mini", captor.getValue().getModel());
    }

    @Test
    void completeReturnsEmptyAndRecordsFallbackOnGatewayError() {
        when(llmProperties.getMaxTokens()).thenReturn(256);
        when(llmProperties.getTemperature()).thenReturn(0.2);
        when(guardianProperties.getTenantId()).thenReturn("company-1");
        when(invocationRepository.save(any(LlmInvocation.class))).thenAnswer(inv -> inv.getArgument(0));

        server.expect(requestTo(GATEWAY + "/api/v1/llm/complete"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        Optional<String> result = adapter.complete(
                LlmPort.LlmRequest.of("prompt", 10, PromptKeys.SRE_INVESTIGATE));

        server.verify();
        assertTrue(result.isEmpty());
        ArgumentCaptor<LlmInvocation> captor = ArgumentCaptor.forClass(LlmInvocation.class);
        verify(invocationRepository).save(captor.capture());
        assertTrue(captor.getValue().isFallbackUsed());
    }

    @Test
    void completeSendsOAuthBearerWhenAvailable() {
        when(llmProperties.getMaxTokens()).thenReturn(16);
        when(llmProperties.getTemperature()).thenReturn(0.2);
        UUID companyId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(guardianProperties.getTenantId()).thenReturn(companyId.toString());
        when(oauthClient.getToken(companyId)).thenReturn(Optional.of("oauth-token"));
        when(invocationRepository.save(any(LlmInvocation.class))).thenAnswer(inv -> inv.getArgument(0));

        server.expect(requestTo(GATEWAY + "/api/v1/llm/complete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer oauth-token"))
                .andRespond(withSuccess("""
                        {"content":"ok","model":"gpt-4.1-mini","providerType":"openai"}
                        """, MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.complete(
                LlmPort.LlmRequest.of("ping", 10, PromptKeys.CODER_HOTFIX));

        server.verify();
        assertEquals("ok", result.orElseThrow());
    }
}
