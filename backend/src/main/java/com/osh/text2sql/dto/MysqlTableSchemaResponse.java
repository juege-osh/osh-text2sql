package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MySQL 表结构响应。
 */
@Data
@Builder
public class MysqlTableSchemaResponse {
    private String database;
    private String tableName;
    private String tableComment;
    private List<java.util.Map<String, Object>> columns;
    private List<java.util.Map<String, Object>> indexes;
}
