package com.keepguard.ms_ai_guardian.application.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClusterStormState {

    private String namespace;
    private UUID incidentId;
    private long startedAtEpochMs;
    private int confirmStreak;
    private boolean nodeNotReady;
    private List<String> affectedServices = new ArrayList<>();

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(UUID incidentId) {
        this.incidentId = incidentId;
    }

    public long getStartedAtEpochMs() {
        return startedAtEpochMs;
    }

    public void setStartedAtEpochMs(long startedAtEpochMs) {
        this.startedAtEpochMs = startedAtEpochMs;
    }

    public int getConfirmStreak() {
        return confirmStreak;
    }

    public void setConfirmStreak(int confirmStreak) {
        this.confirmStreak = confirmStreak;
    }

    public boolean isNodeNotReady() {
        return nodeNotReady;
    }

    public void setNodeNotReady(boolean nodeNotReady) {
        this.nodeNotReady = nodeNotReady;
    }

    public List<String> getAffectedServices() {
        return affectedServices;
    }

    public void setAffectedServices(List<String> affectedServices) {
        this.affectedServices = affectedServices != null ? affectedServices : new ArrayList<>();
    }
}
