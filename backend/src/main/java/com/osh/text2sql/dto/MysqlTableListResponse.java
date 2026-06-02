package com.osh.text2sql.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * MySQL 表列表响应。
 */
@Data
@Builder
public class MysqlTableListResponse {
    private String database;
    private int total;
    private List<MysqlTableListItem> tables;
}
