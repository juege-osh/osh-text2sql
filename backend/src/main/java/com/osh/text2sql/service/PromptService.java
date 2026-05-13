package com.osh.text2sql.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import com.osh.text2sql.dto.KafkaQuerySpec;
import com.osh.text2sql.dto.PromptOutput;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.util.JsonUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private static final PromptTemplate GENERATE_QUERY_TEMPLATE = new PromptTemplate("""
        你是资深数据分析工程师。请根据数据源类型、用户问题与可用结构，生成一个只读查询。
        要求：
        1. 只能返回 JSON。
        2. JSON 字段固定为 query, reasoning, safetyNotes。
        3. MySQL 只能输出单条 SELECT/WITH SQL，不能带分号。
        3.1 如果用户问“用户数量/总用户数/多少用户”，优先选择真正的用户主表，例如 user/users/sys_user。
        3.2 不能因为某张业务表里有 user_id 字段，就把它当成用户主表去统计总用户数。
        3.3 如果上下文里已经明确存在 sys_user 或 user/users 之类用户表，必须优先使用这些表。
        4. Redis 只能输出单条只读命令，列 key 时优先使用 SCAN，禁止使用 KEYS。
        5. Elasticsearch 只能输出查询 body JSON，不要包含 markdown，不要包含 HTTP 方法和 URL，必须包含 _index 字段。
        6. Kafka 只能输出只读查询 DSL JSON，operation 只能是 LIST_TOPICS、DESCRIBE_TOPIC、READ_MESSAGES。
        6.1 Kafka 查询 DSL 字段包括 operation、limit、topic、partition、from、offset、keyContains、valueContains。
        6.1.1 查看 topic 列表时，operation 使用 LIST_TOPICS，可附带 limit。
        6.1.2 查看 topic 详情时，operation 使用 DESCRIBE_TOPIC，并提供 topic。
        6.1.3 查看最近消息时，operation 使用 READ_MESSAGES，并提供 topic、limit、from=LATEST。
        6.1.4 按偏移量读取消息时，operation 使用 READ_MESSAGES，并提供 topic、partition、limit、from=OFFSET、offset。
        6.2 如果用户要“看最近消息/最新消息”，优先用 READ_MESSAGES + from=LATEST。
        6.3 如果用户要“看 topic 列表/有哪些主题”，用 LIST_TOPICS。
        6.4 如果用户要“看 topic 分区/详情”，用 DESCRIBE_TOPIC。
        7. 查询必须尽量精简且可执行。

        数据源类型：{type}
        用户问题：{question}
        数据结构摘要：
        {schema}
        """);

    private static final PromptTemplate EXPLAIN_RESULT_TEMPLATE = new PromptTemplate("""
        你是数据分析助手。请根据用户问题、最终查询和结果，用中文输出简洁结论。
        用户问题：{question}
        数据源类型：{type}
        最终查询：
        {query}
        结果摘要：
        {result}
        """);

    private final ChatClient chatClient;

    @Autowired
    public PromptService(ObjectProvider<ChatClient.Builder> builderProvider) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.chatClient = builder == null ? null : builder.build();
    }

    PromptService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public GeneratedQuery generateQuery(DatasourceType type, String question, DatasourceSchemaResponse schema) {
        if (chatClient == null) {
            return fallbackGenerateQuery(type, question, schema);
        }
        try {
            String content = chatClient.prompt()
                .system("你必须只输出合法 JSON 对象，不要输出 markdown，不要输出解释性前后缀。")
                .messages(new UserMessage(GENERATE_QUERY_TEMPLATE.render(Map.of(
                    "type", type.name(),
                    "question", question,
                    "schema", JsonUtils.toJson(schema.getSchema())
                ))))
                .call()
                .content();
            PromptOutput output = parsePromptOutput(content);
            GeneratedQuery generatedQuery = GeneratedQuery.builder()
                .type(type)
                .query(normalizeGeneratedQuery(type, question, schema, output.getQuery()))
                .reasoning(output.getReasoning())
                .safetyNotes(output.getSafetyNotes())
                .build();
            return normalizeMysqlUserCountQuery(question, schema, generatedQuery);
        } catch (Exception exception) {
            log.warn("AI 生成查询失败，改用规则兜底: {}", exception.getMessage());
            return fallbackGenerateQuery(type, question, schema);
        }
    }

    public String explainResult(String question, QueryExecutionResult result) {
        if (chatClient == null) {
            return fallbackExplainResult(question, result);
        }
        return chatClient.prompt()
            .system("只用中文给出简洁可信的结论，不要捏造不存在的数据。")
            .messages(new UserMessage(EXPLAIN_RESULT_TEMPLATE.render(Map.of(
                "question", question,
                "type", result.getType().name(),
                "query", result.getExecutedQuery(),
                "result", JsonUtils.toJson(Map.of(
                    "summary", result.getSummary(),
                    "columns", result.getColumns(),
                    "rows", result.getRows(),
                    "total", result.getTotal()
                ))
            ))))
            .call()
            .content();
    }

    private GeneratedQuery fallbackGenerateQuery(DatasourceType type, String question, DatasourceSchemaResponse schema) {
        return switch (type) {
            case MYSQL -> fallbackMysqlQuery(question, schema);
            case REDIS -> GeneratedQuery.builder()
                .type(type)
                .query(fallbackRedisQuery(question))
                .reasoning("当前未启用 AI，已按规则生成只读 Redis 命令。")
                .safetyNotes("仅允许白名单内的只读命令。")
                .build();
            case ELASTICSEARCH -> fallbackElasticsearchQuery(question, schema);
            case KAFKA -> fallbackKafkaQuery(question, schema);
        };
    }

    private GeneratedQuery fallbackMysqlQuery(String question, DatasourceSchemaResponse schemaResponse) {
        Map<String, Object> schema = asObject(schemaResponse.getSchema());
        String questionLower = question.toLowerCase(Locale.ROOT);
        String table = resolveMysqlTable(question, schema);
        if (table == null) {
            throw new BadRequestException("当前未启用 AI，无法从问题中确定 MySQL 目标表，请改用 RAW 模式或在问题中明确表名。");
        }

        List<String> columns = extractMysqlColumns(schema.get(table));
        Integer limit = extractLimit(question, question.contains("最近") ? 5 : 10);
        StringBuilder sql = new StringBuilder();

        if (isCountQuestion(questionLower)) {
            sql.append("SELECT COUNT(*) AS ");
            sql.append(isUserLikeName(table) ? "total_users" : "total_count");
            sql.append(" FROM ").append(table);
            String deleteFlag = findColumn(columns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
            if (deleteFlag != null) {
                sql.append(" WHERE ").append(deleteFlag).append(" = 0");
            }
            return GeneratedQuery.builder()
                .type(DatasourceType.MYSQL)
                .query(sql.toString())
                .reasoning("当前未启用 AI，已按问题关键词匹配目标表并生成统计 SQL。")
                .safetyNotes("仅生成单条只读 SELECT 查询。")
                .build();
        }

        List<String> selectedColumns = preferredMysqlProjection(columns);
        sql.append("SELECT ");
        sql.append(String.join(", ", selectedColumns));
        sql.append(" FROM ").append(table);

        List<String> conditions = new ArrayList<>();
        String deleteFlag = findColumn(columns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
        if (deleteFlag != null) {
            conditions.add(deleteFlag + " = 0");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        if (question.contains("最近") || question.contains("最新")) {
            String orderColumn = findColumn(columns, List.of("create_time", "created_at", "gmt_create", "created_time", "update_time", "updated_at"));
            if (orderColumn != null) {
                sql.append(" ORDER BY ").append(orderColumn).append(" DESC");
            }
        } else if (question.contains("最高") || question.contains("最贵") || question.contains("最大")) {
            String orderColumn = findColumn(columns, List.of("price", "sale_price", "amount", "sales", "sale_count", "score", "hot"));
            if (orderColumn != null) {
                sql.append(" ORDER BY ").append(orderColumn).append(" DESC");
            }
        }

        sql.append(" LIMIT ").append(limit);
        return GeneratedQuery.builder()
            .type(DatasourceType.MYSQL)
            .query(sql.toString())
            .reasoning("当前未启用 AI，已按问题关键词和表结构生成兜底 SQL。")
            .safetyNotes("仅生成单条只读 SELECT 查询。")
            .build();
    }

    private GeneratedQuery normalizeMysqlUserCountQuery(String question,
                                                        DatasourceSchemaResponse schemaResponse,
                                                        GeneratedQuery generatedQuery) {
        if (generatedQuery.getType() != DatasourceType.MYSQL) {
            return generatedQuery;
        }
        Map<String, Object> schema = asObject(schemaResponse.getSchema());
        if (!shouldForcePrimaryUserCountQuery(question, schema)) {
            return generatedQuery;
        }
        String table = resolveMysqlTable(question, schema);
        if (table == null) {
            return generatedQuery;
        }
        List<String> columns = extractMysqlColumns(schema.get(table));
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total_users FROM ").append(table);
        String deleteFlag = findColumn(columns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
        if (deleteFlag != null) {
            sql.append(" WHERE ").append(deleteFlag).append(" = 0");
        }
        return GeneratedQuery.builder()
            .type(DatasourceType.MYSQL)
            .query(sql.toString())
            .reasoning(StrUtil.blankToDefault(generatedQuery.getReasoning(), "已自动纠正为用户主表统计 SQL。"))
            .safetyNotes(StrUtil.blankToDefault(generatedQuery.getSafetyNotes(), "仅生成单条只读 SELECT 查询。"))
            .build();
    }

    private GeneratedQuery fallbackElasticsearchQuery(String question, DatasourceSchemaResponse schemaResponse) {
        Map<String, Object> schema = asObject(schemaResponse.getSchema());
        String index = resolveSchemaObjectName(question, schema.keySet().stream().toList());
        if (index == null && schema.size() == 1) {
            index = schema.keySet().iterator().next();
        }
        if (index == null) {
            throw new BadRequestException("当前未启用 AI，无法从问题中确定 Elasticsearch 索引，请改用 RAW 模式或在问题中明确索引名。");
        }

        List<String> fields = toStringList(asObject(schema.get(index)).get("fields"));
        int size = extractLimit(question, 10);
        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("_index", index);
        dsl.put("size", size);

        String keyword = extractKeywordAfter(question, "包含");
        if (keyword != null) {
            String titleField = findColumn(fields, List.of("title", "name", "courseName", "bookName"));
            if (titleField == null) {
                titleField = fields.isEmpty() ? "title" : fields.get(0);
            }
            dsl.put("query", Map.of("match", Map.of(titleField, keyword)));
        } else {
            dsl.put("query", Map.of("match_all", Map.of()));
        }

        if (question.contains("销量最高") || question.contains("最热门") || question.contains("最高")) {
            String orderField = findColumn(fields, List.of("sale_count", "sales", "saleCount", "hot_score", "score", "price"));
            if (orderField != null) {
                dsl.put("sort", List.of(Map.of(orderField, Map.of("order", "desc"))));
            }
        }

        return GeneratedQuery.builder()
            .type(DatasourceType.ELASTICSEARCH)
            .query(JsonUtils.toJson(dsl))
            .reasoning("当前未启用 AI，已按索引名和字段关键词生成兜底 Elasticsearch DSL。")
            .safetyNotes("仅生成 _search 查询 DSL。")
            .build();
    }

    private GeneratedQuery fallbackKafkaQuery(String question, DatasourceSchemaResponse schemaResponse) {
        Map<String, Object> schema = asObject(schemaResponse.getSchema());
        String topic = resolveSchemaObjectName(question, schema.keySet().stream().toList());
        Map<String, Object> spec = new LinkedHashMap<>();

        if (question.contains("topic") && (question.contains("列表") || question.contains("有哪些") || question.contains("列出"))) {
            spec.put("operation", "LIST_TOPICS");
            spec.put("limit", extractLimit(question, 20));
        } else if (question.contains("主题") && (question.contains("列表") || question.contains("有哪些") || question.contains("列出"))) {
            spec.put("operation", "LIST_TOPICS");
            spec.put("limit", extractLimit(question, 20));
        } else if ((question.contains("分区") || question.contains("详情")) && topic != null) {
            spec.put("operation", "DESCRIBE_TOPIC");
            spec.put("topic", topic);
        } else if ((question.contains("消息") || question.contains("最近") || question.contains("最新")) && topic != null) {
            spec.put("operation", "READ_MESSAGES");
            spec.put("topic", topic);
            spec.put("limit", extractLimit(question, 10));
            spec.put("from", "LATEST");
            String keyContains = extractKeywordAfter(question, "key包含");
            if (keyContains != null) {
                spec.put("keyContains", keyContains);
            }
            String valueContains = extractKeywordAfter(question, "value包含");
            if (valueContains != null) {
                spec.put("valueContains", valueContains);
            }
        } else {
            spec.put("operation", "LIST_TOPICS");
            spec.put("limit", extractLimit(question, 20));
        }

        return GeneratedQuery.builder()
            .type(DatasourceType.KAFKA)
            .query(JsonUtils.toJson(spec))
            .reasoning("当前未启用 AI，已按 topic 名和消息查询关键词生成只读 Kafka DSL。")
            .safetyNotes("Kafka 仅支持查看 topic 列表、topic 详情和读取消息。")
            .build();
    }

    private String fallbackRedisQuery(String question) {
        if (question.contains("key") || question.contains("键")) {
            return "SCAN 0";
        }
        return "SCAN 0";
    }

    private String fallbackExplainResult(String question, QueryExecutionResult result) {
        if (result.getRows() != null && result.getRows().size() == 1) {
            Map<String, Object> row = result.getRows().get(0);
            if (row.size() == 1) {
                Map.Entry<String, Object> entry = row.entrySet().iterator().next();
                return "根据查询结果，%s 为 %s。".formatted(entry.getKey(), Objects.toString(entry.getValue(), "null"));
            }
        }
        return switch (result.getType()) {
            case MYSQL -> "查询已完成，%s。".formatted(result.getSummary());
            case REDIS -> "Redis 查询已完成，%s。".formatted(result.getSummary());
            case ELASTICSEARCH -> "Elasticsearch 查询已完成，%s。".formatted(result.getSummary());
            case KAFKA -> "Kafka 查询已完成，%s。".formatted(result.getSummary());
        };
    }

    private boolean isCountQuestion(String questionLower) {
        return questionLower.contains("count")
            || questionLower.contains("多少")
            || questionLower.contains("总共")
            || questionLower.contains("总数")
            || questionLower.contains("数量")
            || questionLower.contains("统计");
    }

    private boolean shouldForcePrimaryUserCountQuery(String question, Map<String, Object> schema) {
        String questionLower = question.toLowerCase(Locale.ROOT);
        if (!isCountQuestion(questionLower)) {
            return false;
        }
        if (!(questionLower.contains("user") || question.contains("用户"))) {
            return false;
        }
        String explicitTable = resolveSchemaObjectName(question, schema.keySet().stream().toList());
        return explicitTable == null || isUserLikeName(explicitTable);
    }

    private String resolveMysqlTable(String question, Map<String, Object> schema) {
        List<String> tableNames = schema.keySet().stream().toList();
        String explicit = resolveSchemaObjectName(question, tableNames);
        if (explicit != null) {
            return explicit;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (question.contains("系统用户") || question.contains("后台用户") || question.contains("管理员")) {
            return tableNames.stream()
                .filter(name -> "sys_user".equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        }
        if (lower.contains("user") || question.contains("用户")) {
            return tableNames.stream()
                .filter(this::isUserLikeName)
                .sorted(Comparator.comparingInt(this::userTablePriority))
                .findFirst()
                .orElse(null);
        }
        return tableNames.stream().findFirst().orElse(null);
    }

    private int userTablePriority(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        if ("osh_user".equals(lower)) {
            return 0;
        }
        if ("user".equals(lower) || "users".equals(lower)) {
            return 1;
        }
        if ("sys_user".equals(lower)) {
            return 2;
        }
        if (lower.endsWith("_user")) {
            return 3;
        }
        return 4;
    }

    private boolean isUserLikeName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("sys_user")
            || lower.equals("osh_user")
            || lower.equals("user")
            || lower.equals("users")
            || lower.endsWith("_user")
            || lower.contains("user");
    }

    private List<String> extractMysqlColumns(Object rawColumns) {
        if (!(rawColumns instanceof List<?> columns)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object column : columns) {
            result.add(Objects.toString(asObject(column).get("columnName"), ""));
        }
        return result.stream().filter(StrUtil::isNotBlank).toList();
    }

    private List<String> preferredMysqlProjection(List<String> columns) {
        List<String> projection = new ArrayList<>();
        for (String candidate : List.of("id", "user_id", "title", "name", "username", "price", "status", "create_time", "created_at", "update_time")) {
            String column = findColumn(columns, List.of(candidate));
            if (column != null && !projection.contains(column)) {
                projection.add(column);
            }
        }
        for (String column : columns) {
            if (!projection.contains(column)) {
                projection.add(column);
            }
            if (projection.size() >= 8) {
                break;
            }
        }
        return projection.isEmpty() ? List.of("*") : projection;
    }

    private String findColumn(List<String> columns, List<String> candidates) {
        for (String candidate : candidates) {
            for (String column : columns) {
                if (column.equalsIgnoreCase(candidate)) {
                    return column;
                }
            }
        }
        for (String candidate : candidates) {
            for (String column : columns) {
                if (column.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))) {
                    return column;
                }
            }
        }
        return null;
    }

    private String resolveSchemaObjectName(String question, List<String> candidates) {
        String lower = question.toLowerCase(Locale.ROOT);
        return candidates.stream()
            .filter(name -> lower.contains(name.toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparingInt(String::length).reversed())
            .findFirst()
            .orElseGet(() -> {
                String token = extractAsciiToken(question);
                if (token == null) {
                    return null;
                }
                return candidates.stream()
                    .filter(name -> name.equalsIgnoreCase(token))
                    .findFirst()
                    .orElse(null);
            });
    }

    private String extractAsciiToken(String question) {
        Matcher matcher = Pattern.compile("([A-Za-z0-9._-]{3,})").matcher(question);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private Integer extractLimit(String question, int fallback) {
        Matcher matcher = NUMBER_PATTERN.matcher(question);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return fallback;
    }

    private String extractKeywordAfter(String question, String marker) {
        int index = question.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String suffix = question.substring(index + marker.length()).trim();
        if (suffix.isEmpty()) {
            return null;
        }
        for (String separator : List.of("，", "。", ",", " ", "\n")) {
            int separatorIndex = suffix.indexOf(separator);
            if (separatorIndex > 0) {
                suffix = suffix.substring(0, separatorIndex).trim();
            }
        }
        return suffix.isEmpty() ? null : suffix;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private PromptOutput parsePromptOutput(String content) {
        String normalized = normalizeJson(content);
        try {
            return JsonUtils.fromJson(normalized, PromptOutput.class);
        } catch (Exception ignore) {
            int firstBrace = normalized.indexOf('{');
            int lastBrace = normalized.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return JsonUtils.fromJson(normalized.substring(firstBrace, lastBrace + 1), PromptOutput.class);
            }
            if (normalized.contains("\"query\"")) {
                return JsonUtils.fromJson(extractLikelyJsonObject(normalized), PromptOutput.class);
            }
            throw ignore;
        }
    }

    private String normalizeJson(String content) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("模型没有返回查询结果");
        }
        String normalized = content.trim();
        if (normalized.startsWith("```json")) {
            normalized = normalized.substring(7);
        }
        if (normalized.startsWith("```")) {
            normalized = normalized.substring(3);
        }
        if (normalized.endsWith("```")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.trim();
    }

    private String extractLikelyJsonObject(String content) {
        Map<String, Object> wrapper = JsonUtils.fromJson(content, new TypeReference<>() {
        });
        return JsonUtils.toJson(wrapper);
    }

    private String stringifyQuery(Object query) {
        if (query == null) {
            return null;
        }
        if (query instanceof String text) {
            return text;
        }
        if (query instanceof Map<?, ?> map) {
            return JsonUtils.toJson(new LinkedHashMap<>(map));
        }
        return JsonUtils.toJson(query);
    }

    private String normalizeGeneratedQuery(DatasourceType type,
                                          String question,
                                          DatasourceSchemaResponse schema,
                                          Object rawQuery) {
        String query = stringifyQuery(rawQuery);
        if (query == null) {
            return null;
        }
        query = query.trim();
        if (type == DatasourceType.MYSQL && query.endsWith(";")) {
            query = query.substring(0, query.length() - 1);
        }
        return query;
    }
}
