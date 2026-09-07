package com.keepguard.ms_ai_guardian.adapters.in.rest.incident.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedIncidentResponseDTO {
    private List<IncidentListItemResponseDTO> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
