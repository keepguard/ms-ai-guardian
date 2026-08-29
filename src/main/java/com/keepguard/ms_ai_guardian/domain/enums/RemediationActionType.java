package com.keepguard.ms_ai_guardian.domain.enums;

public enum RemediationActionType {
    RECREATE_POD("Recriar o pod"),
    ROLLOUT_RESTART("Rollout restart"),
    ROLLBACK_REVISION("Rollback da revisão"),
    SCALE_REPLAY("Zerar e subir réplicas"),
    DISMISS("Dispensar incidente");

    private final String label;

    RemediationActionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
