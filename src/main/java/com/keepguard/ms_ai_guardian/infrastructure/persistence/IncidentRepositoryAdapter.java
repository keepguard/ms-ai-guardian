package com.keepguard.ms_ai_guardian.infrastructure.persistence;

import com.keepguard.ms_ai_guardian.application.port.out.persistence.IncidentRepositoryPort;
import com.keepguard.ms_ai_guardian.domain.entity.Incident;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentSeverity;
import com.keepguard.ms_ai_guardian.domain.enums.IncidentStatus;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.entity.IncidentJpaEntity;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.mapper.IncidentJpaMapper;
import com.keepguard.ms_ai_guardian.infrastructure.persistence.spring.IncidentSpringRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IncidentRepositoryAdapter implements IncidentRepositoryPort {

    private final IncidentSpringRepository springRepository;
    private final IncidentJpaMapper mapper;

    @Override
    public Incident save(Incident incident) {
        return mapper.toDomain(springRepository.save(mapper.toEntity(incident)));
    }

    @Override
    public Optional<Incident> findById(UUID id) {
        return springRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Page<Incident> search(Map<String, String> query, Pageable pageable) {
        return springRepository.findAll(specification(query), pageable).map(mapper::toDomain);
    }

    @Override
    public List<Incident> findByNamespaceOrderByCreatedAtDesc(String namespace) {
        return springRepository.findByNamespaceOrderByCreatedAtDesc(namespace).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Incident> findFirstByFingerprintOrderByCreatedAtDesc(String fingerprint) {
        return springRepository.findFirstByFingerprintOrderByCreatedAtDesc(fingerprint).map(mapper::toDomain);
    }

    @Override
    public Optional<Incident> findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(String podName, LocalDateTime after) {
        return springRepository.findTopByPodNameAndCreatedAtAfterOrderByCreatedAtDesc(podName, after)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Incident> findTopByServiceNameAndErrorReasonAndCreatedAtAfterOrderByCreatedAtDesc(
            String serviceName, String errorReason, LocalDateTime after) {
        return springRepository.findTopByServiceNameAndErrorReasonAndCreatedAtAfterOrderByCreatedAtDesc(
                        serviceName, errorReason, after)
                .map(mapper::toDomain);
    }

    @Override
    public List<Incident> findByStatusIn(Collection<IncidentStatus> statuses) {
        return springRepository.findByStatusIn(statuses).stream().map(mapper::toDomain).toList();
    }

    private Specification<IncidentJpaEntity> specification(Map<String, String> query) {
        return (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            String namespace = query.get("namespace");
            if (namespace != null && !namespace.isBlank()) {
                predicates.add(cb.equal(root.get("namespace"), namespace));
            }
            addEnum(predicates, cb, root.get("status"), query.get("status"), IncidentStatus.class);
            addEnum(predicates, cb, root.get("severity"), query.get("severity"), IncidentSeverity.class);
            eq(predicates, cb, root.get("serviceName"), query.get("serviceName"));
            eq(predicates, cb, root.get("k8sConclusion"), query.get("k8sConclusion"));
            eq(predicates, cb, root.get("errorReason"), query.get("errorReason"));
            eq(predicates, cb, root.get("correlationId"), query.get("correlationId"));
            LocalDateTime from = parseTime(query.get("from"));
            LocalDateTime to = parseTime(query.get("to"));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            String search = query.get("q");
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("serviceName")), like),
                        cb.like(cb.lower(root.get("podName")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("aiSummary"), "")), like)
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void eq(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Path<String> path, String value) {
        if (value != null && !value.isBlank()) {
            predicates.add(cb.equal(path, value));
        }
    }

    private static <E extends Enum<E>> void addEnum(List<Predicate> predicates,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Path<E> path, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            predicates.add(cb.equal(path, Enum.valueOf(type, value)));
        } catch (IllegalArgumentException ignored) {
            // filtro inválido ignorado
        }
    }

    private static LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toLocalDateTime();
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
