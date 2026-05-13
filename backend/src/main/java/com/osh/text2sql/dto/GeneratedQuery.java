package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeneratedQuery {
    private DatasourceType type;
    private String query;
    private String reasoning;
    private String safetyNotes;
}
