package com.keepguard.ms_ai_guardian.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestStatusTest {

    @Test
    void activeStatusesAreTheOpenCycle() {
        assertTrue(PullRequestStatus.OPEN.isActive());
        assertTrue(PullRequestStatus.CHANGES_REQUESTED.isActive());
        assertTrue(PullRequestStatus.AI_APPROVED.isActive());
        assertFalse(PullRequestStatus.MERGED_BY_HUMAN.isActive());
        assertFalse(PullRequestStatus.DEPLOYED.isActive());
        assertFalse(PullRequestStatus.CLOSED.isActive());
        assertTrue(PullRequestStatus.active().contains(PullRequestStatus.OPEN));
    }
}
