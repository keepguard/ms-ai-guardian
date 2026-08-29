package com.keepguard.ms_ai_guardian.domain.enums;

public enum K8sConclusion {
    CONTROLLER_ALREADY_RETRYING,
    REPLICAS_INTENTIONALLY_ZERO,
    UNSCHEDULABLE,
    IMAGE_OR_CONFIG,
    NODE_FAILURE,
    NO_CONTROLLER,
    TRANSIENT_INFRA_RECOVERABLE
}
