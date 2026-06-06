package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

/**
 * MySQL 表列表项。
 */
@Data
@Builder
public class MysqlTableListItem {
    private String tableName;
    private String tableComment;
}
