package com.keepguard.ms_ai_guardian.application.port.out.persistence;

import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;

public interface LlmInvocationRepositoryPort {
    LlmInvocation save(LlmInvocation invocation);
}
