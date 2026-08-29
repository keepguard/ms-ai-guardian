package com.keepguard.ms_ai_guardian.application.service.sre;

import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.entity.IncidentLifecycleEvent;
import com.keepguard.ms_ai_guardian.domain.enums.LifecycleEventType;
import com.keepguard.ms_ai_guardian.domain.repository.IncidentLifecycleEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IncidentLifecycleService {

    private final IncidentLifecycleEventRepository repository;

    public void record(Incident incident, LifecycleEventType type, String detail) {
        repository.save(IncidentLifecycleEvent.builder()
                .incidentId(incident.getId())
                .eventType(type)
                .detail(detail)
                .correlationId(incident.getCorrelationId())
                .build());
    }
}
