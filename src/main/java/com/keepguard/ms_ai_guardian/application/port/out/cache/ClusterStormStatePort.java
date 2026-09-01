package com.keepguard.ms_ai_guardian.application.port.out.cache;

import com.keepguard.ms_ai_guardian.application.dto.ClusterStormState;

import java.util.Optional;

public interface ClusterStormStatePort {

    Optional<ClusterStormState> get(String namespace);

    void save(String namespace, ClusterStormState state, int ttlSeconds);

    void clear(String namespace);
}
