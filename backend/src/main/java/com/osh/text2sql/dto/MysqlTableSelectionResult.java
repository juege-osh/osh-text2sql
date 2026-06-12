package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * MySQL 选表结果
 */
@Data
@Builder
public class MysqlTableSelectionResult {
    private List<String> tables;
    private String reason;
    private List<String> focus;
    private String rawResponse;
}
