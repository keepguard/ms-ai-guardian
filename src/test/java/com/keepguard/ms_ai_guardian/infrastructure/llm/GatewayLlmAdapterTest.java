package com.keepguard.ms_ai_guardian.infrastructure.llm;

import com.keepguard.ms_ai_guardian.adapters.out.feign.LlmGatewayClient;
import com.keepguard.ms_ai_guardian.application.port.out.auth.AuthTokenPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.LlmPort;
import com.keepguard.ms_ai_guardian.application.port.out.llm.PromptKeys;
import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import com.keepguard.ms_ai_guardian.application.port.out.persistence.LlmInvocationRepositoryPort;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianLlmProperties;
import com.keepguard.ms_ai_guardian.infrastructure.config.GuardianProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayLlmAdapterTest {

    private static final String GATEWAY = "http://llm.test";

    @Mock
    private GuardianLlmProperties llmProperties;
    @Mock
    private GuardianProperties guardianProperties;
    @Mock
    private LlmInvocationRepositoryPort invocationRepository;
    @Mock
    private AuthTokenPort authTokenPort;
    @Mock
    private LlmGatewayClient llmGatewayClient;

    private GatewayLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(llmProperties.getGatewayUrl()).thenReturn(GATEWAY);
        lenient().when(llmProperties.isEnabled()).thenReturn(true);
        lenient().when(authTokenPort.getToken(any())).thenReturn(Optional.empty());
        adapter = new GatewayLlmAdapter(
                llmProperties, guardianProperties, invocationRepository, authTokenPort, llmGatewayClient);
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
        when(llmGatewayClient.complete(any(), eq("company-1"), any(), isNull()))
                .thenReturn(new GatewayLlmDtos.CompleteResponse(
                        "causa raiz em português", "gpt-4.1-mini", "openai", null));

        UUID incidentId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Optional<String> result = adapter.complete(new LlmPort.LlmRequest(
                "diagnóstico do pod", 10, PromptKeys.SRE_INVESTIGATE, "classpath", incidentId));

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
        when(llmGatewayClient.complete(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        Optional<String> result = adapter.complete(
                LlmPort.LlmRequest.of("prompt", 10, PromptKeys.SRE_INVESTIGATE));

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
        when(authTokenPort.getToken(companyId)).thenReturn(Optional.of("oauth-token"));
        when(invocationRepository.save(any(LlmInvocation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(llmGatewayClient.complete(any(), eq(companyId.toString()), any(), eq("Bearer oauth-token")))
                .thenReturn(new GatewayLlmDtos.CompleteResponse("ok", "gpt-4.1-mini", "openai", null));

        Optional<String> result = adapter.complete(
                LlmPort.LlmRequest.of("ping", 10, PromptKeys.CODER_HOTFIX));

        assertEquals("ok", result.orElseThrow());
    }
}
