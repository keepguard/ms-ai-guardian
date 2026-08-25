package com.keepguard.ms_ai_guardian.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pull_request_lifecycles", schema = "ms_ai_guardian")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestLifecycle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "base_branch", nullable = false)
    private String baseBranch;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url")
    private String prUrl;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "ai_reviewed")
    private boolean aiReviewed;

    @Column(name = "ai_approved")
    private boolean aiApproved;

    @Column(name = "ai_review_feedback", columnDefinition = "TEXT")
    private String aiReviewFeedback;

    @Column(name = "human_approved")
    private boolean humanApproved;

    @Column(name = "merged_by_human")
    private boolean mergedByHuman;

    @Column(name = "deployed_to_k8s")
    private boolean deployedToK8s;

    @Column(name = "last_processed_comment_id")
    private String lastProcessedCommentId;

    @Column(name = "status", nullable = false)
    private String status; // OPEN, CHANGES_REQUESTED, AI_APPROVED, MERGED_BY_HUMAN, DEPLOYED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
