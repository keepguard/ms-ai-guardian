package com.keepguard.ms_ai_guardian.domain.enums;

public enum LifecycleEventType {
    DETECTED,
    INVESTIGATED,
    ALERTED,
    ACTION_APPLIED,
    ACTION_FAILED,
    HEALTH_CHECK_PASS,
    HEALTH_CHECK_FAIL,
    NORMALIZED,
    DISMISSED,
    REOPENED
}
