package com.keepguard.ms_ai_guardian.domain;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

public final class GuardianClusterConstants {

    public static final String CLUSTER_SERVICE_NAME = "__cluster__";
    public static final String CLUSTER_POD_NAME = "cluster-outage";
    public static final String CLUSTER_ERROR_REASON = "CLUSTER_WIDE_OUTAGE";

    private GuardianClusterConstants() {
    }

    public static String clusterFingerprint(String namespace) {
        String ns = namespace != null ? namespace.trim().toLowerCase() : "keepguard";
        return DigestUtils.md5DigestAsHex(("cluster:storm:" + ns).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isClusterIncident(String serviceName) {
        return CLUSTER_SERVICE_NAME.equals(serviceName);
    }
}
