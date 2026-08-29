package com.keepguard.ms_ai_guardian.application.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaginatedIncidentResponse {
    private List<IncidentListItemDTO> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
