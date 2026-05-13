package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class QueryExecutionResult {
    private DatasourceType type;
    private String executedQuery;
    private String queryLanguage;
    private String summary;
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private long total;
    private long elapsedMs;
    private Object rawResponse;
}
