package com.osh.text2sql.dto;

import java.util.List;
import lombok.Data;

/**
 * HBase 查询 DSL。
 */
@Data
public class HbaseQuerySpec {
    private String operation;
    private String namespace;
    private String table;
    private String rowKey;
    private String rowKeyPrefix;
    private Integer limit;
    private Integer maxVersions;
    private List<String> columns;
}
