package com.keepguard.ms_ai_guardian.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_comments", schema = "ms_ai_guardian")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedCommentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "comment_id", unique = true, nullable = false)
    private String commentId;

    @Column(name = "pr_number", nullable = false)
    private Integer prNumber;

    @CreationTimestamp
    @Column(name = "processed_at", updatable = false)
    private LocalDateTime processedAt;
}
