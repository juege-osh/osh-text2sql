package com.osh.text2sql.service;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.KnowledgeImportResponse;
import com.osh.text2sql.introspect.MysqlIntrospector;
import com.osh.text2sql.introspect.MysqlSchemaCachePayload;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * MySQL 知识导入服务
 */
@Service
public class MysqlKnowledgeImportService {
    private static final int TABLES_PER_DOCUMENT = 15;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Text2SqlProperties properties;
    private final ConnectionProfileResolver profileResolver;
    private final MysqlIntrospector mysqlIntrospector;
    private final QaKnowledgeImportService qaKnowledgeImportService;

    public MysqlKnowledgeImportService(Text2SqlProperties properties,
                                       ConnectionProfileResolver profileResolver,
                                       MysqlIntrospector mysqlIntrospector,
                                       QaKnowledgeImportService qaKnowledgeImportService) {
        this.properties = properties;
        this.profileResolver = profileResolver;
        this.mysqlIntrospector = mysqlIntrospector;
        this.qaKnowledgeImportService = qaKnowledgeImportService;
    }

    public KnowledgeImportResponse importMysqlSchemaKnowledge() {
        ConnectionProfile profile = profileResolver.resolve(null, com.osh.text2sql.dto.DatasourceType.MYSQL);
        List<MysqlSchemaCachePayload.TableMeta> tables = mysqlIntrospector.loadTableList(profile);
        Text2SqlProperties.QaKnowledgeImportProperties qaConfig = properties.getQaKnowledgeImport();
        return qaKnowledgeImportService.importMarkdownDocuments(
            qaConfig.getBaseUrl(),
            qaConfig.getUsername(),
            qaConfig.getPassword(),
            qaConfig.getLibId(),
            "mysql-schema",
            buildMysqlSchemaDocuments(profile, tables)
        );
    }

    public KnowledgeImportResponse importMysqlIndexKnowledge() {
        ConnectionProfile profile = profileResolver.resolve(null, com.osh.text2sql.dto.DatasourceType.MYSQL);
        List<MysqlSchemaCachePayload.TableMeta> tables = mysqlIntrospector.loadTableList(profile);
        Text2SqlProperties.QaKnowledgeImportProperties qaConfig = properties.getQaKnowledgeImport();
        return qaKnowledgeImportService.importMarkdownDocuments(
            qaConfig.getBaseUrl(),
            qaConfig.getUsername(),
            qaConfig.getPassword(),
            qaConfig.getLibId(),
            "mysql-index",
            buildMysqlIndexDocuments(profile, tables)
        );
    }

    private Map<String, String> buildMysqlSchemaDocuments(ConnectionProfile profile, List<MysqlSchemaCachePayload.TableMeta> tables) {
        Map<String, String> documents = new LinkedHashMap<>();
        String dateSuffix = LocalDate.now().format(DATE_FORMATTER);
        documents.put("backstage-mysql-schema-overview-%s.md".formatted(dateSuffix), buildMysqlSchemaOverviewMarkdown(profile, tables));
        int batch = 1;
        for (int start = 0; start < tables.size(); start += TABLES_PER_DOCUMENT) {
            int end = Math.min(start + TABLES_PER_DOCUMENT, tables.size());
            List<MysqlSchemaCachePayload.TableMeta> subList = tables.subList(start, end);
            String fileName = "backstage-mysql-schema-part-%02d-%s.md".formatted(batch++, dateSuffix);
            documents.put(fileName, buildMysqlSchemaBatchMarkdown(profile, subList, start + 1, end, tables.size()));
        }
        return documents;
    }

    private Map<String, String> buildMysqlIndexDocuments(ConnectionProfile profile, List<MysqlSchemaCachePayload.TableMeta> tables) {
        Map<String, String> documents = new LinkedHashMap<>();
        String dateSuffix = LocalDate.now().format(DATE_FORMATTER);
        documents.put("backstage-mysql-index-overview-%s.md".formatted(dateSuffix), buildMysqlIndexOverviewMarkdown(profile, tables));
        int batch = 1;
        for (int start = 0; start < tables.size(); start += TABLES_PER_DOCUMENT) {
            int end = Math.min(start + TABLES_PER_DOCUMENT, tables.size());
            List<MysqlSchemaCachePayload.TableMeta> subList = tables.subList(start, end);
            String fileName = "backstage-mysql-index-part-%02d-%s.md".formatted(batch++, dateSuffix);
            documents.put(fileName, buildMysqlIndexBatchMarkdown(profile, subList, start + 1, end, tables.size()));
        }
        return documents;
    }

    private String buildMysqlSchemaOverviewMarkdown(ConnectionProfile profile, List<MysqlSchemaCachePayload.TableMeta> tables) {
        StringBuilder builder = new StringBuilder();
        builder.append("# backstage MySQL 表结构总览\n\n");
        builder.append("- 导出时间: ").append(LocalDateTime.now()).append("\n");
        builder.append("- 数据库: ").append(profile.getDatabase()).append("\n");
        builder.append("- 主机: ").append(profile.getHost()).append(":").append(profile.getPort()).append("\n");
        builder.append("- 表数量: ").append(tables.size()).append("\n\n");

        builder.append("## 总览\n\n");
        builder.append("当前知识库用于沉淀 `backstage` MySQL 库的表结构、字段定义和表注释，供智能问数、选表和结构理解使用。\n\n");
        builder.append("文档拆分策略：\n");
        builder.append("- `backstage-mysql-schema-overview-日期.md`：总览和表清单\n");
        builder.append("- `backstage-mysql-schema-part-xx-日期.md`：按批次拆分的表结构详情\n\n");

        builder.append("## 表清单\n\n");
        for (MysqlSchemaCachePayload.TableMeta table : tables) {
            builder.append("- ").append(table.tableName());
            if (table.tableComment() != null && !table.tableComment().isBlank()) {
                builder.append("：").append(table.tableComment());
            }
            builder.append("\n");
        }
        builder.append("\n");

        builder.append("## 使用建议\n\n");
        builder.append("- 先根据表注释和表名定位业务表\n");
        builder.append("- 生成 SQL 前优先根据字段名和字段注释理解业务语义\n");
        builder.append("- 如果多个表字段名相近，先结合表注释判断表的职责范围\n");

        return builder.toString();
    }

    private String buildMysqlSchemaBatchMarkdown(ConnectionProfile profile,
                                                 List<MysqlSchemaCachePayload.TableMeta> tables,
                                                 int startIndex,
                                                 int endIndex,
                                                 int total) {
        StringBuilder builder = new StringBuilder();
        builder.append("# backstage MySQL 表结构分批文档\n\n");
        builder.append("- 数据库: ").append(profile.getDatabase()).append("\n");
        builder.append("- 批次范围: 第 ").append(startIndex).append(" 到 ").append(endIndex).append(" 张表，共 ").append(total).append(" 张\n\n");
        Map<String, List<Map<String, Object>>> columnsByTable = mysqlIntrospector.loadTableColumnsBatch(profile, tables);
        for (MysqlSchemaCachePayload.TableMeta table : tables) {
            appendSchemaTableSection(builder, table, columnsByTable.getOrDefault(table.tableName(), List.of()));
        }
        return builder.toString();
    }

    private String buildMysqlIndexOverviewMarkdown(ConnectionProfile profile, List<MysqlSchemaCachePayload.TableMeta> tables) {
        StringBuilder builder = new StringBuilder();
        builder.append("# backstage MySQL 索引总览\n\n");
        builder.append("- 导出时间: ").append(LocalDateTime.now()).append("\n");
        builder.append("- 数据库: ").append(profile.getDatabase()).append("\n");
        builder.append("- 主机: ").append(profile.getHost()).append(":").append(profile.getPort()).append("\n");
        builder.append("- 表数量: ").append(tables.size()).append("\n\n");

        builder.append("## 总览\n\n");
        builder.append("当前知识库用于沉淀 `backstage` MySQL 库的索引设计，供 SQL 优化、执行风险判断和查询路径分析使用。\n\n");
        builder.append("文档拆分策略：\n");
        builder.append("- `backstage-mysql-index-overview-日期.md`：索引知识总览\n");
        builder.append("- `backstage-mysql-index-part-xx-日期.md`：按批次拆分的索引详情\n\n");
        return builder.toString();
    }

    private String buildMysqlIndexBatchMarkdown(ConnectionProfile profile,
                                                List<MysqlSchemaCachePayload.TableMeta> tables,
                                                int startIndex,
                                                int endIndex,
                                                int total) {
        StringBuilder builder = new StringBuilder();
        builder.append("# backstage MySQL 索引分批文档\n\n");
        builder.append("- 数据库: ").append(profile.getDatabase()).append("\n");
        builder.append("- 批次范围: 第 ").append(startIndex).append(" 到 ").append(endIndex).append(" 张表，共 ").append(total).append(" 张\n\n");
        Map<String, List<Map<String, Object>>> indexesByTable = mysqlIntrospector.loadTableIndexesBatch(profile, tables);
        for (MysqlSchemaCachePayload.TableMeta table : tables) {
            appendIndexTableSection(builder, table, indexesByTable.getOrDefault(table.tableName(), List.of()));
        }
        return builder.toString();
    }

    private void appendSchemaTableSection(StringBuilder builder,
                                          MysqlSchemaCachePayload.TableMeta table,
                                          List<Map<String, Object>> columns) {
        builder.append("## ").append(table.tableName()).append("\n\n");
        builder.append("- 表注释: ").append(table.tableComment() == null || table.tableComment().isBlank() ? "无" : table.tableComment()).append("\n");
        builder.append("- 字段数: ").append(columns.size()).append("\n\n");

        builder.append("### 字段定义\n\n");
        for (Map<String, Object> column : columns) {
            builder.append("- ")
                .append(column.getOrDefault("columnName", ""))
                .append(" | 类型: ")
                .append(column.getOrDefault("dataType", ""))
                .append(" | 注释: ")
                .append(column.getOrDefault("columnComment", ""))
                .append("\n");
        }
        builder.append("\n");
    }

    private void appendIndexTableSection(StringBuilder builder,
                                         MysqlSchemaCachePayload.TableMeta table,
                                         List<Map<String, Object>> indexes) {
        builder.append("## ").append(table.tableName()).append("\n\n");
        builder.append("- 表注释: ").append(table.tableComment() == null || table.tableComment().isBlank() ? "无" : table.tableComment()).append("\n");
        builder.append("- 索引项数: ").append(indexes.size()).append("\n\n");
        builder.append("### 索引定义\n\n");
        if (indexes.isEmpty()) {
            builder.append("- 无索引信息\n\n");
            return;
        }
        for (Map<String, Object> index : indexes) {
            builder.append("- ")
                .append(index.getOrDefault("indexName", ""))
                .append(" | 字段: ")
                .append(index.getOrDefault("columnName", ""))
                .append(" | 类型: ")
                .append(index.getOrDefault("indexType", ""))
                .append(" | 唯一: ")
                .append("0".equals(String.valueOf(index.getOrDefault("nonUnique", ""))) ? "是" : "否")
                .append(" | 顺序: ")
                .append(index.getOrDefault("seqInIndex", ""))
                .append("\n");
        }
        builder.append("\n");
    }
}
