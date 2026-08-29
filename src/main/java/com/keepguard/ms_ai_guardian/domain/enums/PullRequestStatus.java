package com.keepguard.ms_ai_guardian.domain.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PullRequestStatus {
    OPEN,
    CHANGES_REQUESTED,
    AI_APPROVED,
    MERGED_BY_HUMAN,
    DEPLOYED,
    CLOSED;

    public boolean isActive() {
        return this == OPEN || this == CHANGES_REQUESTED || this == AI_APPROVED;
    }

    public static Set<PullRequestStatus> active() {
        return EnumSet.of(OPEN, CHANGES_REQUESTED, AI_APPROVED);
    }
}
