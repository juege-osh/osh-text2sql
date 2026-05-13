package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryResponse {
    private DatasourceSchemaResponse schema;
    private GeneratedQuery generatedQuery;
    private QueryExecutionResult result;
    private String answer;
}
