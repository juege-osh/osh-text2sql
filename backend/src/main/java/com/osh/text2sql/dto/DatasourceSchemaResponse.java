package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DatasourceSchemaResponse {
    private DatasourceType type;
    private String name;
    private String summary;
    private Object schema;
}
