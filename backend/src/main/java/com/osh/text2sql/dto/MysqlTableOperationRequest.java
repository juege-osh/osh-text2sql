package com.osh.text2sql.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * MySQL 单表操作请求。
 */
@Data
public class MysqlTableOperationRequest {
    private ConnectionProfile connection;

    @NotBlank
    private String tableName;
}
