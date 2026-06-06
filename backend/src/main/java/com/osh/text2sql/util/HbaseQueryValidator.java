package com.osh.text2sql.util;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.dto.HbaseQuerySpec;
import com.osh.text2sql.exception.BadRequestException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HBase 查询 DSL 校验器。
 */
public final class HbaseQueryValidator {

    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
        "LIST_TABLES",
        "DESCRIBE_TABLE",
        "GET_ROW",
        "SCAN_ROWS",
        "COUNT_ROWS"
    );

    private HbaseQueryValidator() {
    }

    public static HbaseQuerySpec validate(String queryJson) {
        if (StrUtil.isBlank(queryJson)) {
            throw new BadRequestException("HBase 查询 DSL 不能为空");
        }
        HbaseQuerySpec spec = JsonUtils.fromJson(queryJson, HbaseQuerySpec.class);
        if (spec == null || StrUtil.isBlank(spec.getOperation())) {
            throw new BadRequestException("HBase 查询 DSL 缺少 operation");
        }
        String operation = spec.getOperation().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OPERATIONS.contains(operation)) {
            throw new BadRequestException("HBase 仅支持 LIST_TABLES、DESCRIBE_TABLE、GET_ROW、SCAN_ROWS、COUNT_ROWS");
        }
        spec.setOperation(operation);
        if (StrUtil.isBlank(spec.getNamespace())) {
            spec.setNamespace("default");
        } else {
            spec.setNamespace(spec.getNamespace().trim());
        }
        if (spec.getLimit() == null) {
            spec.setLimit("LIST_TABLES".equals(operation) ? 50 : 20);
        }
        if (spec.getLimit() < 1 || spec.getLimit() > 200) {
            throw new BadRequestException("HBase 查询 limit 仅允许 1 到 200");
        }
        if (spec.getMaxVersions() == null) {
            spec.setMaxVersions(1);
        }
        if (spec.getMaxVersions() < 1 || spec.getMaxVersions() > 10) {
            throw new BadRequestException("HBase 查询 maxVersions 仅允许 1 到 10");
        }
        if (spec.getColumns() == null) {
            spec.setColumns(List.of());
        } else {
            spec.setColumns(spec.getColumns().stream().filter(StrUtil::isNotBlank).map(String::trim).toList());
        }

        switch (operation) {
            case "LIST_TABLES" -> {
            }
            case "DESCRIBE_TABLE" -> requireTable(spec);
            case "GET_ROW" -> {
                requireTable(spec);
                if (StrUtil.isBlank(spec.getRowKey())) {
                    throw new BadRequestException("GET_ROW 必须提供 rowKey");
                }
                spec.setRowKey(spec.getRowKey().trim());
            }
            case "SCAN_ROWS", "COUNT_ROWS" -> {
                requireTable(spec);
                if (StrUtil.isNotBlank(spec.getRowKeyPrefix())) {
                    spec.setRowKeyPrefix(spec.getRowKeyPrefix().trim());
                }
            }
            default -> throw new BadRequestException("不支持的 HBase 操作");
        }
        return spec;
    }

    private static void requireTable(HbaseQuerySpec spec) {
        if (StrUtil.isBlank(spec.getTable())) {
            throw new BadRequestException("HBase 查询 DSL 缺少 table");
        }
        spec.setTable(spec.getTable().trim());
    }
}
