package com.osh.text2sql.executor;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.HbaseQuerySpec;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.introspect.HbaseSupport;
import com.osh.text2sql.util.HbaseQueryValidator;
import com.osh.text2sql.util.JsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.stereotype.Component;

/**
 * HBase 查询执行器。
 */
@Component
public class HbaseQueryExecutor implements QueryExecutor {

    private final HbaseSupport hbaseSupport;

    public HbaseQueryExecutor(HbaseSupport hbaseSupport) {
        this.hbaseSupport = hbaseSupport;
    }

    @Override
    public QueryExecutionResult execute(ConnectionProfile profile, String query) {
        HbaseQuerySpec spec = HbaseQueryValidator.validate(query);
        long start = System.currentTimeMillis();
        return switch (spec.getOperation()) {
            case "LIST_TABLES" -> executeListTables(profile, spec, start);
            case "DESCRIBE_TABLE" -> executeDescribeTable(profile, spec, start);
            case "GET_ROW" -> executeGetRow(profile, spec, start);
            case "SCAN_ROWS" -> executeScanRows(profile, spec, start);
            case "COUNT_ROWS" -> executeCountRows(profile, spec, start);
            default -> throw new BadRequestException("不支持的 HBase 操作");
        };
    }

    private QueryExecutionResult executeListTables(ConnectionProfile profile, HbaseQuerySpec spec, long start) {
        try (Connection connection = hbaseSupport.createConnection(profile);
             Admin admin = connection.getAdmin()) {
            TableName[] tableNames = admin.listTableNamesByNamespace(spec.getNamespace());
            List<Map<String, Object>> rows = java.util.Arrays.stream(tableNames)
                .map(tableName -> Map.<String, Object>of(
                    "namespace", tableName.getNamespaceAsString(),
                    "table", tableName.getQualifierAsString()
                ))
                .sorted(java.util.Comparator.comparing(row -> String.valueOf(row.get("table"))))
                .limit(spec.getLimit())
                .toList();
            return buildResult(spec, start, "共发现 %d 张 HBase 表".formatted(tableNames.length), List.of("namespace", "table"), rows, Map.of("tables", rows, "namespace", spec.getNamespace()));
        } catch (Exception exception) {
            throw new IllegalStateException("HBase 表列表读取失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeDescribeTable(ConnectionProfile profile, HbaseQuerySpec spec, long start) {
        try (Connection connection = hbaseSupport.createConnection(profile);
             Admin admin = connection.getAdmin()) {
            var descriptor = admin.getDescriptor(tableName(spec));
            List<Map<String, Object>> rows = new ArrayList<>();
            descriptor.getColumnFamilies();
            for (var family : descriptor.getColumnFamilies()) {
                rows.add(Map.of(
                    "table", spec.getTable(),
                    "family", family.getNameAsString(),
                    "maxVersions", family.getMaxVersions(),
                    "compression", family.getCompressionType().name()
                ));
            }
            return buildResult(spec, start, "表 %s 共 %d 个列族".formatted(spec.getTable(), rows.size()), List.of("table", "family", "maxVersions", "compression"), rows, Map.of("descriptor", rows));
        } catch (Exception exception) {
            throw new IllegalStateException("HBase 表结构读取失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeGetRow(ConnectionProfile profile, HbaseQuerySpec spec, long start) {
        try (Connection connection = hbaseSupport.createConnection(profile);
             Table table = connection.getTable(tableName(spec))) {
            Get get = new Get(Bytes.toBytes(spec.getRowKey()));
            get.readVersions(spec.getMaxVersions());
            applyColumns(get, spec.getColumns());
            Result result = table.get(get);
            List<Map<String, Object>> rows = result.isEmpty() ? List.of() : List.of(flattenResult(result));
            return buildResult(spec, start, rows.isEmpty() ? "未查询到指定 rowKey" : "rowKey %s 查询成功".formatted(spec.getRowKey()), rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()), rows, Map.of("rowKey", spec.getRowKey(), "rows", rows));
        } catch (Exception exception) {
            throw new IllegalStateException("HBase 行读取失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeScanRows(ConnectionProfile profile, HbaseQuerySpec spec, long start) {
        try (Connection connection = hbaseSupport.createConnection(profile);
             Table table = connection.getTable(tableName(spec))) {
            Scan scan = new Scan();
            scan.readVersions(spec.getMaxVersions());
            scan.setLimit(spec.getLimit());
            if (spec.getRowKeyPrefix() != null && !spec.getRowKeyPrefix().isBlank()) {
                scan.setRowPrefixFilter(Bytes.toBytes(spec.getRowKeyPrefix()));
            }
            applyColumns(scan, spec.getColumns());
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultScanner scanner = table.getScanner(scan)) {
                for (Result result : scanner) {
                    rows.add(flattenResult(result));
                    if (rows.size() >= spec.getLimit()) {
                        break;
                    }
                }
            }
            List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
            return buildResult(spec, start, "扫描到 %d 行结果".formatted(rows.size()), columns, rows, Map.of("rows", rows, "rowKeyPrefix", spec.getRowKeyPrefix()));
        } catch (Exception exception) {
            throw new IllegalStateException("HBase Scan 失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeCountRows(ConnectionProfile profile, HbaseQuerySpec spec, long start) {
        try (Connection connection = hbaseSupport.createConnection(profile);
             Table table = connection.getTable(tableName(spec))) {
            Scan scan = new Scan();
            if (spec.getRowKeyPrefix() != null && !spec.getRowKeyPrefix().isBlank()) {
                scan.setRowPrefixFilter(Bytes.toBytes(spec.getRowKeyPrefix()));
            }
            scan.setLimit(spec.getLimit());
            int count = 0;
            try (ResultScanner scanner = table.getScanner(scan)) {
                for (Result ignored : scanner) {
                    count++;
                    if (count >= spec.getLimit()) {
                        break;
                    }
                }
            }
            List<Map<String, Object>> rows = List.of(Map.of("row_count", count));
            return buildResult(spec, start, "统计结果为 %d".formatted(count), List.of("row_count"), rows, Map.of("row_count", count));
        } catch (Exception exception) {
            throw new IllegalStateException("HBase Count 失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult buildResult(HbaseQuerySpec spec,
                                             long start,
                                             String summary,
                                             List<String> columns,
                                             List<Map<String, Object>> rows,
                                             Object rawResponse) {
        return QueryExecutionResult.builder()
            .type(DatasourceType.HBASE)
            .executedQuery(compactQuery(spec))
            .queryLanguage("HBase Query DSL")
            .summary(summary)
            .columns(columns)
            .rows(rows)
            .total(rows.size())
            .elapsedMs(System.currentTimeMillis() - start)
            .rawResponse(rawResponse)
            .build();
    }

    private TableName tableName(HbaseQuerySpec spec) {
        return TableName.valueOf(spec.getNamespace(), spec.getTable());
    }

    private void applyColumns(Get get, List<String> columns) {
        for (String column : columns) {
            String[] parts = column.split(":", 2);
            if (parts.length == 2) {
                get.addColumn(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
            } else if (parts.length == 1) {
                get.addFamily(Bytes.toBytes(parts[0]));
            }
        }
    }

    private void applyColumns(Scan scan, List<String> columns) {
        for (String column : columns) {
            String[] parts = column.split(":", 2);
            if (parts.length == 2) {
                scan.addColumn(Bytes.toBytes(parts[0]), Bytes.toBytes(parts[1]));
            } else if (parts.length == 1) {
                scan.addFamily(Bytes.toBytes(parts[0]));
            }
        }
    }

    private Map<String, Object> flattenResult(Result result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rowKey", Bytes.toString(result.getRow()));
        for (Cell cell : result.rawCells()) {
            String family = Bytes.toString(CellUtil.cloneFamily(cell));
            String qualifier = Bytes.toString(CellUtil.cloneQualifier(cell));
            String key = qualifier == null || qualifier.isBlank() ? family : family + ":" + qualifier;
            row.put(key, Bytes.toString(CellUtil.cloneValue(cell)));
        }
        return row;
    }

    private String compactQuery(HbaseQuerySpec spec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", spec.getOperation());
        if (spec.getNamespace() != null) payload.put("namespace", spec.getNamespace());
        if (spec.getTable() != null) payload.put("table", spec.getTable());
        if (spec.getRowKey() != null) payload.put("rowKey", spec.getRowKey());
        if (spec.getRowKeyPrefix() != null) payload.put("rowKeyPrefix", spec.getRowKeyPrefix());
        if (spec.getLimit() != null) payload.put("limit", spec.getLimit());
        if (spec.getMaxVersions() != null) payload.put("maxVersions", spec.getMaxVersions());
        if (spec.getColumns() != null && !spec.getColumns().isEmpty()) payload.put("columns", spec.getColumns());
        return JsonUtils.toJson(payload);
    }
}
