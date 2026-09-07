package com.keepguard.ms_ai_guardian.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplate {

    private UUID id;

    private String promptKey;

    private String version;

    private String body;

    private String status;

    private String checksum;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
