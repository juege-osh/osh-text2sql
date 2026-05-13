package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuerySuggestion {
    private DatasourceType type;
    private String title;
    private String prompt;
}
