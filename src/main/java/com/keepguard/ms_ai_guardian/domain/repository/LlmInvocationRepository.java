package com.keepguard.ms_ai_guardian.domain.repository;

import com.keepguard.ms_ai_guardian.domain.entity.LlmInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LlmInvocationRepository extends JpaRepository<LlmInvocation, UUID> {
}
