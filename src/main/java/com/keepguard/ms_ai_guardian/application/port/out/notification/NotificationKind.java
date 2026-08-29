package com.keepguard.ms_ai_guardian.application.port.out.notification;

public enum NotificationKind {
    INCIDENT_DIAGNOSTIC("incident-diagnostic"),
    PR_OPENED("pr-opened"),
    PR_READY_FOR_APPROVAL("pr-ready-for-approval"),
    COMMENT_REPLIED("comment-replied"),
    DEPLOY_STARTED("deploy-started"),
    DEPLOY_COMPLETED("deploy-completed"),
    DATA_INCONSISTENCY("data-inconsistency"),
    INFRASTRUCTURE_ALERT("infrastructure-alert"),
    MESA("mesa");

    private final String templateName;

    NotificationKind(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() {
        return templateName;
    }
}
