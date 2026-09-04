package com.keepguard.ms_ai_guardian.domain.enums;

public enum ActionRisk {
    LOW("baixo"),
    HIGH("alto"),
    DESTRUCTIVE("destrutivo");

    private final String label;

    ActionRisk(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
