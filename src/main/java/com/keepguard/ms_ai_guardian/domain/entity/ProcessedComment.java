package com.keepguard.ms_ai_guardian.domain.entity;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedComment {

    private UUID id;

    private String commentId;

    private Integer prNumber;

    private LocalDateTime processedAt;
}
