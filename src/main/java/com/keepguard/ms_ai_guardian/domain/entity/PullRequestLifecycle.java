package com.keepguard.ms_ai_guardian.domain.entity;

import com.keepguard.ms_ai_guardian.domain.enums.PullRequestStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestLifecycle {

    private UUID id;

    private UUID incidentId;

    private String repoName;

    private String branchName;

    private String baseBranch;

    private Integer prNumber;

    private String prUrl;

    private String filePath;

    private boolean aiReviewed;

    private boolean aiApproved;

    private String aiReviewFeedback;

    private boolean humanApproved;

    private boolean mergedByHuman;

    private boolean deployedToK8s;

    private String lastProcessedCommentId;

    private PullRequestStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
