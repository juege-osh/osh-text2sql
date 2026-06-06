package com.osh.text2sql.introspect;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.MysqlTableSchemaResponse;
import com.osh.text2sql.config.Text2SqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MysqlIntrospector implements DatasourceIntrospector {

    private static final Logger log = LoggerFactory.getLogger(MysqlIntrospector.class);
    private static final int MAX_TABLES = 30;
    private static final int MAX_COLUMNS = 12;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 10000;

    private final MysqlSchemaCacheService cacheService;
    private final MysqlQueryAnalyzer queryAnalyzer;
    private final Text2SqlProperties properties;

    @Autowired
    public MysqlIntrospector(MysqlSchemaCacheService cacheService, Text2SqlProperties properties) {
        this.cacheService = cacheService;
        this.queryAnalyzer = new MysqlQueryAnalyzer();
        this.properties = properties;
    }

    @Override
    public DatasourceSchemaResponse introspect(ConnectionProfile profile) {
        return introspect(profile, null);
    }

    public DatasourceSchemaResponse introspect(ConnectionProfile profile, @Nullable String question) {
        long start = System.currentTimeMillis();
        DatasourceSchemaResponse response;
        if (question != null && !question.isBlank()) {
            Optional<MysqlSchemaCachePayload> cachedColumnsPayload = cacheService.getSchemaColumnsOnly(profile)
                .filter(this::hasTableMetadata);
            if (cachedColumnsPayload.isPresent()) {
                response = buildResponse(cachedColumnsPayload.get(), question, true, profile);
            } else {
                log.info("MySQL 结构缓存未命中，开始按当前问题从 MySQL 读取候选表结构：database={}", profile.getDatabase());
                response = loadQuestionScopedSchema(profile, question);
            }
        } else {
            Optional<MysqlSchemaCachePayload> cachedPayload = cacheService.getSchema(profile)
                .filter(this::hasTableMetadata);
            if (cachedPayload.isPresent()) {
                response = buildResponse(cachedPayload.get(), null, false, profile);
            } else {
                log.info("MySQL 结构缓存未命中，开始从 MySQL 读取全量结构：database={}", profile.getDatabase());
                MysqlSchemaCachePayload payload = refreshCache(profile);
                response = buildResponse(payload, null, false, profile);
            }
        }
        log.info("MySQL 结构分析完成：database={}, questionPresent={}, elapsedMs={}",
            profile.getDatabase(), question != null && !question.isBlank(), System.currentTimeMillis() - start);
        return response;
    }

    public DatasourceSchemaResponse refresh(ConnectionProfile profile, @Nullable String question) {
        long start = System.currentTimeMillis();
        MysqlSchemaCachePayload payload = refreshCache(profile);
        DatasourceSchemaResponse response = buildResponse(payload, question, false, profile);
        log.info("MySQL 结构刷新完成：database={}, elapsedMs={}",
            profile.getDatabase(), System.currentTimeMillis() - start);
        return response;
    }

    protected DatasourceSchemaResponse loadSchema(ConnectionProfile profile, @Nullable String question) {
        return buildResponse(loadCachePayload(profile), question, false, profile);
    }

    public MysqlSchemaCachePayload loadCachePayload(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        jdbcTemplate.setQueryTimeout(properties.getDefaultTimeoutSeconds());
        List<MysqlSchemaCachePayload.TableMeta> tables = loadTableList(profile);
        Map<String, Object> schema = loadTableSchemaBatch(jdbcTemplate, profile.getDatabase(), tables);
        MysqlSchemaCachePayload payload = new MysqlSchemaCachePayload(
            profile.getDatabase(),
            "MySQL 库 %s，全量缓存 %d 张表结构".formatted(profile.getDatabase(), tables.size()),
            schema,
            tables
        );
        log.info("MySQL 全量结构已读取：database={}, tableCount={}, elapsedMs={}",
            profile.getDatabase(), tables.size(), System.currentTimeMillis() - start);
        return payload;
    }

    public List<MysqlSchemaCachePayload.TableMeta> loadTableList(ConnectionProfile profile) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        jdbcTemplate.setQueryTimeout(properties.getDefaultTimeoutSeconds());
        List<Map<String, Object>> tableRows = jdbcTemplate.queryForList("""
            SELECT table_name AS tableName, table_comment AS tableComment
            FROM information_schema.tables
            WHERE table_schema = ?
            ORDER BY table_name
            """, profile.getDatabase());
        return tableRows.stream()
            .map(this::toTableMeta)
            .toList();
    }

    public MysqlTableSchemaResponse loadTableSchemaLive(ConnectionProfile profile, String tableName) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        jdbcTemplate.setQueryTimeout(properties.getDefaultTimeoutSeconds());
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
            SELECT column_name AS columnName, data_type AS dataType, column_comment AS columnComment
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """, profile.getDatabase(), tableName);
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT
                index_name AS indexName,
                column_name AS columnName,
                non_unique AS nonUnique,
                seq_in_index AS seqInIndex,
                index_type AS indexType
            FROM information_schema.statistics
            WHERE table_schema = ? AND table_name = ?
            ORDER BY index_name, seq_in_index
            """, profile.getDatabase(), tableName);
        String tableComment = jdbcTemplate.queryForObject("""
            SELECT table_comment
            FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?
            """, String.class, profile.getDatabase(), tableName);
        return MysqlTableSchemaResponse.builder()
            .database(profile.getDatabase())
            .tableName(tableName)
            .tableComment(tableComment == null ? "" : tableComment)
            .columns(columns)
            .indexes(indexes)
            .build();
    }

    public Map<String, List<Map<String, Object>>> loadTableColumnsBatch(ConnectionProfile profile,
                                                                        List<MysqlSchemaCachePayload.TableMeta> tables) {
        if (tables == null || tables.isEmpty()) {
            return Map.of();
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        jdbcTemplate.setQueryTimeout(properties.getDefaultTimeoutSeconds());
        List<String> tableNames = tables.stream()
            .map(MysqlSchemaCachePayload.TableMeta::tableName)
            .toList();
        return queryColumnsByTables(jdbcTemplate, profile.getDatabase(), tableNames);
    }

    public Map<String, List<Map<String, Object>>> loadTableIndexesBatch(ConnectionProfile profile,
                                                                        List<MysqlSchemaCachePayload.TableMeta> tables) {
        if (tables == null || tables.isEmpty()) {
            return Map.of();
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        jdbcTemplate.setQueryTimeout(properties.getDefaultTimeoutSeconds());
        List<String> tableNames = tables.stream()
            .map(MysqlSchemaCachePayload.TableMeta::tableName)
            .toList();
        return queryIndexesByTables(jdbcTemplate, profile.getDatabase(), tableNames);
    }

    private DatasourceSchemaResponse loadQuestionScopedSchema(ConnectionProfile profile, String question) {
        long start = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        jdbcTemplate.setQueryTimeout(properties.getDefaultTimeoutSeconds());
        List<Map<String, Object>> tableRows = jdbcTemplate.queryForList("""
            SELECT table_name AS tableName, table_comment AS tableComment
            FROM information_schema.tables
            WHERE table_schema = ?
            ORDER BY table_name
            """, profile.getDatabase());
        List<MysqlSchemaCachePayload.TableMeta> tables = tableRows.stream()
            .map(this::toTableMeta)
            .toList();
        MysqlQueryPlan plan = queryAnalyzer.analyze(question, tables, Map.of(), MAX_TABLES);
        List<MysqlSchemaCachePayload.TableMeta> selectedTables = tables.stream()
            .filter(table -> plan.candidateTables().contains(table.tableName()))
            .sorted(Comparator.comparingInt(table -> plan.candidateTables().indexOf(table.tableName())))
            .limit(plan.candidateLimit())
            .toList();
        Map<String, Object> schema = loadTableSchemaBatch(jdbcTemplate, profile.getDatabase(), selectedTables);
        DatasourceSchemaResponse response = DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .name(profile.getDatabase())
            .summary(buildSummary(profile.getDatabase(), question, schema.size()))
            .schema(schema)
            .build();
        log.info("MySQL 按问题筛选的结构已读取：database={}, candidateLimit={}, selectedTableCount={}, preferredTable={}, selectedTables={}, elapsedMs={}",
            profile.getDatabase(), plan.candidateLimit(), schema.size(), plan.preferredTable(), selectedTables.stream().map(MysqlSchemaCachePayload.TableMeta::tableName).toList(), System.currentTimeMillis() - start);
        return response;
    }

    private DatasourceSchemaResponse buildResponse(MysqlSchemaCachePayload payload,
                                                  @Nullable String question,
                                                  boolean indexesDeferred,
                                                  ConnectionProfile profile) {
        List<MysqlSchemaCachePayload.TableMeta> sourceTables = payload.tables().isEmpty() ? defaultTablesFromSchema(payload) : payload.tables();
        List<MysqlSchemaCachePayload.TableMeta> rankedTables;
        if (question == null || question.isBlank()) {
            rankedTables = defaultTables(sourceTables);
        } else {
            MysqlQueryPlan plan = queryAnalyzer.analyze(question, sourceTables, payload.schema(), MAX_TABLES);
            rankedTables = sourceTables.stream()
                .filter(table -> plan.candidateTables().contains(table.tableName()))
                .sorted(Comparator.comparingInt(table -> plan.candidateTables().indexOf(table.tableName())))
                .limit(plan.candidateLimit())
                .toList();
            log.info("MySQL 缓存结构已按问题重排：database={}, candidateLimit={}, preferredTable={}, selectedTables={}",
                payload.name(), plan.candidateLimit(), plan.preferredTable(), rankedTables.stream().map(MysqlSchemaCachePayload.TableMeta::tableName).toList());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        for (MysqlSchemaCachePayload.TableMeta table : rankedTables) {
            String tableName = table.tableName();
            Object columns = payload.schema().get(tableName);
            if (columns != null) {
                schema.put(tableName, columns);
            }
        }
        if (indexesDeferred && !schema.isEmpty()) {
            cacheService.attachIndexes(
                profile,
                schema,
                rankedTables.stream().map(MysqlSchemaCachePayload.TableMeta::tableName).toList()
            );
        }
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .name(payload.name())
            .summary(buildSummary(payload.name(), question, schema.size()))
            .schema(schema)
            .build();
    }

    private Map<String, Object> loadTableSchemaBatch(JdbcTemplate jdbcTemplate,
                                                     String database,
                                                     List<MysqlSchemaCachePayload.TableMeta> tables) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            return schema;
        }
        List<String> tableNames = tables.stream()
            .map(MysqlSchemaCachePayload.TableMeta::tableName)
            .toList();
        Map<String, List<Map<String, Object>>> columnsByTable = queryColumnsByTables(jdbcTemplate, database, tableNames);
        Map<String, List<Map<String, Object>>> indexesByTable = queryIndexesByTables(jdbcTemplate, database, tableNames);
        for (MysqlSchemaCachePayload.TableMeta table : tables) {
            List<Map<String, Object>> indexes = indexesByTable.getOrDefault(table.tableName(), List.of());
            schema.put(table.tableName(), Map.of(
                "columns", summarizeColumns(columnsByTable.getOrDefault(table.tableName(), List.of()), indexes),
                "indexes", indexes
            ));
        }
        return schema;
    }

    private Map<String, List<Map<String, Object>>> queryColumnsByTables(JdbcTemplate jdbcTemplate,
                                                                        String database,
                                                                        List<String> tableNames) {
        String placeholders = String.join(", ", Collections.nCopies(tableNames.size(), "?"));
        String sql = """
            SELECT table_name AS tableName, column_name AS columnName, data_type AS dataType, column_comment AS columnComment, ordinal_position AS ordinalPosition
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name IN (%s)
            ORDER BY table_name, ordinal_position
            """.formatted(placeholders);
        Object[] params = new Object[tableNames.size() + 1];
        params[0] = database;
        for (int index = 0; index < tableNames.size(); index++) {
            params[index + 1] = tableNames.get(index);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
        Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String tableName = String.valueOf(row.get("tableName"));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("columnName", row.get("columnName"));
            normalized.put("dataType", row.get("dataType"));
            normalized.put("columnComment", row.get("columnComment"));
            List<Map<String, Object>> columns = grouped.computeIfAbsent(tableName, key -> new ArrayList<>());
            if (columns.size() < MAX_COLUMNS) {
                columns.add(normalized);
            }
        }
        return grouped;
    }

    /**
     * 保留结构摘要的精简体积，同时确保 delete_flag、status 和索引列不会被前 20 列截断掉。
     */
    private List<Map<String, Object>> summarizeColumns(List<Map<String, Object>> columns,
                                                       List<Map<String, Object>> indexes) {
        if (columns.size() <= MAX_COLUMNS) {
            return columns;
        }
        Set<String> indexedColumns = indexes.stream()
            .map(index -> String.valueOf(index.get("columnName")))
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        Set<String> priorityColumns = Set.of("delete_flag", "deleted", "is_deleted", "del_flag", "status");
        List<Map<String, Object>> selected = new ArrayList<>();

        for (Map<String, Object> column : columns) {
            String columnName = String.valueOf(column.get("columnName")).toLowerCase(Locale.ROOT);
        if ((priorityColumns.contains(columnName) || indexedColumns.contains(columnName)) && selected.size() < MAX_COLUMNS) {
                selected.add(column);
            }
        }
        for (Map<String, Object> column : columns) {
            if (selected.size() >= MAX_COLUMNS) {
                break;
            }
            if (!selected.contains(column)) {
                selected.add(column);
            }
        }
        selected.sort(Comparator.comparingInt(column -> columns.indexOf(column)));
        return selected;
    }

    private Map<String, List<Map<String, Object>>> queryIndexesByTables(JdbcTemplate jdbcTemplate,
                                                                        String database,
                                                                        List<String> tableNames) {
        String placeholders = String.join(", ", Collections.nCopies(tableNames.size(), "?"));
        String sql = """
            SELECT
                table_name AS tableName,
                index_name AS indexName,
                column_name AS columnName,
                non_unique AS nonUnique,
                seq_in_index AS seqInIndex,
                index_type AS indexType
            FROM information_schema.statistics
            WHERE table_schema = ? AND table_name IN (%s)
            ORDER BY table_name, index_name, seq_in_index
            """.formatted(placeholders);
        Object[] params = new Object[tableNames.size() + 1];
        params[0] = database;
        for (int index = 0; index < tableNames.size(); index++) {
            params[index + 1] = tableNames.get(index);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
        Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String tableName = String.valueOf(row.get("tableName"));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("indexName", row.get("indexName"));
            normalized.put("columnName", row.get("columnName"));
            normalized.put("nonUnique", row.get("nonUnique"));
            normalized.put("seqInIndex", row.get("seqInIndex"));
            normalized.put("indexType", row.get("indexType"));
            grouped.computeIfAbsent(tableName, key -> new ArrayList<>()).add(normalized);
        }
        return grouped;
    }

    private MysqlSchemaCachePayload refreshCache(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        log.info("开始刷新 MySQL 结构缓存：database={}", profile.getDatabase());
        MysqlSchemaCachePayload payload = loadCachePayload(profile);
        cacheService.putSchema(profile, payload);
        log.info("MySQL 结构缓存写入完成：database={}, tableCount={}, elapsedMs={}",
            profile.getDatabase(), payload.tables().size(), System.currentTimeMillis() - start);
        return payload;
    }

    private List<MysqlSchemaCachePayload.TableMeta> defaultTables(List<MysqlSchemaCachePayload.TableMeta> tables) {
        return tables.stream().limit(Math.min(8, MAX_TABLES)).toList();
    }

    private List<MysqlSchemaCachePayload.TableMeta> defaultTablesFromSchema(MysqlSchemaCachePayload payload) {
        return payload.schema().keySet().stream()
            .sorted()
            .map(tableName -> new MysqlSchemaCachePayload.TableMeta(tableName, ""))
            .toList();
    }

    @Override
    public ConnectionTestResponse test(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        List<Map<String, Object>> preview = jdbcTemplate.queryForList("SELECT DATABASE() AS currentDatabase, NOW() AS currentTime");
        return ConnectionTestResponse.builder()
            .success(true)
            .message("MySQL 连接成功")
            .elapsedMs(Duration.ofMillis(System.currentTimeMillis() - start).toMillis())
            .preview(preview)
            .build();
    }

    private DataSource createDataSource(ConnectionProfile profile) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("""
            jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&connectTimeout=%d&socketTimeout=%d
            """.formatted(
            profile.getHost(),
            profile.getPort(),
            profile.getDatabase(),
            CONNECT_TIMEOUT_MS,
            SOCKET_TIMEOUT_MS
        ));
        dataSource.setUsername(profile.getUsername());
        dataSource.setPassword(profile.getPassword());
        return dataSource;
    }

    private List<MysqlSchemaCachePayload.TableMeta> rankTables(List<MysqlSchemaCachePayload.TableMeta> tables, @Nullable String question) {
        if (tables.size() <= MAX_TABLES) {
            return tables;
        }
        if (question == null || question.isBlank()) {
            return tables.stream().limit(MAX_TABLES).toList();
        }

        Set<String> keywords = extractKeywords(question);
        List<MysqlSchemaCachePayload.TableMeta> scored = new ArrayList<>(tables);
        scored.sort(Comparator
            .comparingInt((MysqlSchemaCachePayload.TableMeta table) -> scoreTable(table, keywords)).reversed()
            .thenComparing(MysqlSchemaCachePayload.TableMeta::tableName));

        List<MysqlSchemaCachePayload.TableMeta> top = scored.stream()
            .limit(MAX_TABLES)
            .collect(Collectors.toCollection(ArrayList::new));

        boolean hasUserLikeTable = top.stream().anyMatch(table -> isUserLikeTable(table.tableName()));
        if (!hasUserLikeTable && keywords.stream().anyMatch(this::isUserKeyword)) {
            scored.stream()
                .filter(table -> isUserLikeTable(table.tableName()))
                .findFirst()
                .ifPresent(candidate -> {
                    if (!top.contains(candidate)) {
                        top.set(top.size() - 1, candidate);
                    }
                });
        }
        return top;
    }

    private int scoreTable(MysqlSchemaCachePayload.TableMeta table, Set<String> keywords) {
        String tableName = table.tableName().toLowerCase(Locale.ROOT);
        String tableComment = table.tableComment() == null ? "" : table.tableComment().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (tableName.contains(keyword)) {
                score += 8;
            }
            if (tableComment.contains(keyword)) {
                score += 6;
            }
            if (isUserKeyword(keyword) && isUserLikeTable(tableName)) {
                score += 40;
            }
        }
        if (isUserLikeTable(tableName)) {
            score += 2;
        }
        return score;
    }

    private Set<String> extractKeywords(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        Set<String> keywords = new java.util.LinkedHashSet<>();
        if (lower.contains("user")) keywords.add("user");
        if (question.contains("用户")) keywords.add("用户");
        if (lower.contains("comment")) keywords.add("comment");
        if (question.contains("评论")) keywords.add("评论");
        if (lower.contains("feedback")) keywords.add("feedback");
        if (question.contains("反馈")) keywords.add("反馈");
        if (lower.contains("post")) keywords.add("post");
        if (question.contains("帖子")) keywords.add("帖子");
        if (lower.contains("course")) keywords.add("course");
        if (question.contains("课程")) keywords.add("课程");
        if (lower.contains("book")) keywords.add("book");
        if (question.contains("电子书")) keywords.add("电子书");
        return keywords;
    }

    private boolean isUserLikeTable(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        return lower.equals("sys_user")
            || lower.equals("user")
            || lower.equals("users")
            || lower.endsWith("_user")
            || lower.contains("user");
    }

    private boolean isUserKeyword(String keyword) {
        return "user".equals(keyword) || "用户".equals(keyword);
    }

    private String buildSummary(String database, @Nullable String question, int selectedCount) {
        if (question == null || question.isBlank()) {
            return "MySQL 库 %s，展示前 %d 张表及字段摘要".formatted(database, selectedCount);
        }
        return "MySQL 库 %s，按当前问题筛选出最相关的 %d 张表及字段摘要".formatted(database, selectedCount);
    }

    private boolean hasTableMetadata(MysqlSchemaCachePayload payload) {
        return payload.tables() != null
            && !payload.tables().isEmpty()
            && payload.tables().stream().allMatch(table -> table.tableName() != null && !table.tableName().isBlank());
    }

    private MysqlSchemaCachePayload.TableMeta toTableMeta(Map<String, Object> row) {
        return new MysqlSchemaCachePayload.TableMeta(
            String.valueOf(row.get("tableName")),
            String.valueOf(row.getOrDefault("tableComment", ""))
        );
    }
}
