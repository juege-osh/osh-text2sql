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
import com.osh.text2sql.introspect.MysqlQueryAnalyzer;
import com.osh.text2sql.introspect.MysqlQueryIntent;
import com.osh.text2sql.introspect.MysqlQueryPlan;
import com.osh.text2sql.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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

    private static final String GENERATE_QUERY_COMMON_RULES = """
        你是资深数据分析工程师。请根据数据源类型、用户问题与可用结构，生成一个只读查询。
        要求：
        1. 只能返回 JSON。
        2. JSON 字段固定为 query, reasoning, safetyNotes。
        3. 查询必须尽量精简且可执行。
        """;

    private static final String GENERATE_QUERY_MYSQL_RULES = """
        MySQL 规则：
        1. 只能输出单条 SELECT/WITH SQL，不能带分号。
        2. 如果用户问“用户数量/总用户数/多少用户”，优先选择真正的用户主表，例如 user/users/sys_user。
        3. 不能因为某张业务表里有 user_id 字段，就把它当成用户主表去统计总用户数。
        4. 如果上下文里已经明确存在 sys_user 或 user/users 之类用户表，必须优先使用这些表。
        5. 如果目标表存在 delete_flag 字段，且用户没有明确要求查询已删除数据、全部数据或忽略删除状态，默认必须追加 delete_flag 的有效值条件，并结合字段注释、字段语义、表语义判断有效值，例如优先考虑 delete_flag = 0 表示未删除。
        6. 如果目标表存在 status 字段，且用户问题包含“可用、启用、上架、生效、有效、正常”等语义，默认必须追加 status 的有效状态值条件，并结合字段注释、字段语义、表语义判断具体取值，不能忽略 status 的值。
        7. 当目标表同时存在 delete_flag 和 status 时，优先同时考虑这两个字段及其取值，不要只加字段名不判断值，也不要只追加其中一个条件。
        8. 如果无法从上下文、字段注释、字段命名或表语义判断 status / delete_flag 的有效值，可以保守省略对应条件，但不要臆造取值。
        9. 业务表表名大多由 osh_ 前缀组成，业务问题尽量优先看这些表。
        10. 如果结构摘要中提供了 indexes 信息，优先使用有索引的字段作为 WHERE 条件、ORDER BY 字段、主键字段和常用过滤字段。
        11. 如果问题是在问指定用户各工具的可用次数、剩余次数或配额明细，优先查询用户工具配额表，例如 osh_user_tool_quota，返回 tool_id、remaining_count 等明细列，不要改写成用户总数统计。
        """;

    private static final String GENERATE_QUERY_REDIS_RULES = """
        Redis 规则：
        1. 只能输出单条只读命令。
        2. 列 key 时优先使用 SCAN，禁止使用 KEYS。
        """;

    private static final String GENERATE_QUERY_ES_RULES = """
        Elasticsearch 规则：
        1. 只能输出查询 body JSON。
        2. 不要包含 markdown，不要包含 HTTP 方法和 URL。
        3. 必须包含 _index 字段。
        """;

    private static final String GENERATE_QUERY_KAFKA_RULES = """
        Kafka 规则：
        1. 只能输出只读查询 DSL JSON。
        2. operation 只能是 LIST_TOPICS、DESCRIBE_TOPIC、READ_MESSAGES、COUNT_MESSAGES、COUNT_UNCONSUMED_MESSAGES。
        3. Kafka 查询 DSL 字段包括 operation、limit、topic、consumerGroup、partition、from、offset、keyContains、valueContains。
        4. 查看 topic 列表时，operation 使用 LIST_TOPICS，可附带 limit。
        5. 查看 topic 详情时，operation 使用 DESCRIBE_TOPIC，并提供 topic。
        6. 查看最近消息时，operation 使用 READ_MESSAGES，并提供 topic、limit、from=LATEST。
        7. 按偏移量读取消息时，operation 使用 READ_MESSAGES，并提供 topic、partition、limit、from=OFFSET、offset。
        8. 如果用户要统计某个 topic 一共有多少条消息、消息总数、累计消息位点，使用 COUNT_MESSAGES，并提供 topic。
        9. 如果用户要统计某个 topic 对某个 consumer group 还有多少消息未消费、消费积压多少，使用 COUNT_UNCONSUMED_MESSAGES，并同时提供 topic 和 consumerGroup。
        10. 如果用户问未消费消息、消费积压，但没有明确 consumerGroup，不要猜测，不要退化成 COUNT_MESSAGES，必须返回缺少 consumerGroup 的语义。
        11. COUNT_MESSAGES 和 COUNT_UNCONSUMED_MESSAGES 都不用于读取消息内容，不要附带 keyContains、valueContains、partition、from、offset。
        12. 如果用户要“看最近消息/最新消息”，优先用 READ_MESSAGES + from=LATEST。
        13. 如果用户要“看 topic 列表/有哪些主题”，用 LIST_TOPICS。
        14. 如果用户要“看 topic 分区/详情”，用 DESCRIBE_TOPIC。
        """;

    private static final PromptTemplate EXPLAIN_RESULT_TEMPLATE = new PromptTemplate("""
        你是数据分析助手。请根据用户问题、最终查询和结果，用中文输出简洁结论。
        用户问题：{question}
        数据源类型：{type}
        最终查询：
        {query}
        结果摘要：
        {result}
        """);

    private static final PromptTemplate GENERATE_QUERY_TEMPLATE = new PromptTemplate("{prompt}");

    private final AiChatClientFactory aiChatClientFactory;
    private final MysqlQueryAnalyzer mysqlQueryAnalyzer = new MysqlQueryAnalyzer();
    private final Environment environment;

    @Autowired
    public PromptService(AiChatClientFactory aiChatClientFactory, Environment environment) {
        this.aiChatClientFactory = aiChatClientFactory;
        this.environment = environment;
    }

    PromptService(ChatClient chatClient) {
        this.aiChatClientFactory = null;
        this.environment = null;
    }

    @PostConstruct
    void logAiConfiguration() {
        if (environment == null) {
            return;
        }
        boolean chatClientEnabled = environment.getProperty("spring.ai.chat.client.enabled", Boolean.class, false);
        boolean dashscopeEnabled = environment.getProperty("spring.ai.dashscope.enabled", Boolean.class, false);
        boolean dashscopeChatEnabled = environment.getProperty("spring.ai.dashscope.chat.enabled", Boolean.class, false);
        String model = environment.getProperty("spring.ai.dashscope.chat.options.model", "");
        boolean apiKeyPresent = StrUtil.isNotBlank(environment.getProperty("spring.ai.dashscope.api-key"));
        String provider = aiChatClientFactory == null ? "unknown" : aiChatClientFactory.currentProvider().name();
        String reasoningEffort = aiChatClientFactory == null ? "(unknown)" : StrUtil.blankToDefault(aiChatClientFactory.currentReasoningEffort(), "(empty)");
        String activeBaseUrl = aiChatClientFactory == null ? "(unknown)" : StrUtil.blankToDefault(aiChatClientFactory.currentBaseUrl(), "(empty)");
        String activeModel = aiChatClientFactory == null ? "(unknown)" : StrUtil.blankToDefault(aiChatClientFactory.currentModel(), "(empty)");
        String completionsPath = aiChatClientFactory == null ? "(unknown)" : StrUtil.blankToDefault(aiChatClientFactory.currentCompletionsPath(), "(empty)");
        log.info(
            "AI 配置已加载：provider={}, reasoningEffort={}, chatClientEnabled={}, dashscopeEnabled={}, dashscopeChatEnabled={}, apiKeyPresent={}, model={}, baseUrl={}, completionsPath={}, chatClientAvailable={}",
            provider,
            reasoningEffort,
            chatClientEnabled,
            dashscopeEnabled,
            dashscopeChatEnabled,
            apiKeyPresent,
            StrUtil.blankToDefault(model, "(empty)"),
            activeBaseUrl,
            completionsPath,
            currentChatClient() != null
        );
    }

    public GeneratedQuery generateQuery(DatasourceType type, String question, DatasourceSchemaResponse schema) {
        ChatClient chatClient = currentChatClient();
        if (chatClient == null) {
            log.warn("AI 查询路径不可用：当前 ChatClient 不可用，type={}, question={}", type, question);
            throw new BadRequestException("当前 AI 查询能力不可用，请稍后重试或改用手动模式");
        }
        try {
            long promptBuildStart = System.currentTimeMillis();
            String prompt = buildGenerateQueryPrompt(type, question, schema);
            long promptBuildElapsed = System.currentTimeMillis() - promptBuildStart;
            log.info("AI 查询路径已启用：provider={}, reasoningEffort={}, baseUrl={}, model={}, completionsPath={}, type={}, question={}",
                currentProviderName(), currentReasoningEffort(), currentBaseUrl(), currentModel(), currentCompletionsPath(), type, question);
            long aiCallStart = System.currentTimeMillis();
            String content = chatClient.prompt()
                .system("你必须只输出合法 JSON 对象，不要输出 markdown，不要输出解释性前后缀。")
                .messages(new UserMessage(GENERATE_QUERY_TEMPLATE.render(Map.of(
                    "prompt", prompt
                ))))
                .call()
                .content();
            long aiCallElapsed = System.currentTimeMillis() - aiCallStart;
            long parseStart = System.currentTimeMillis();
            PromptOutput output = parsePromptOutput(content);
            long parseElapsed = System.currentTimeMillis() - parseStart;
            long normalizeStart = System.currentTimeMillis();
            GeneratedQuery generatedQuery = GeneratedQuery.builder()
                .type(type)
                .query(normalizeGeneratedQuery(type, question, schema, output.getQuery()))
                .reasoning(output.getReasoning())
                .safetyNotes(output.getSafetyNotes())
                .build();
            long normalizeElapsed = System.currentTimeMillis() - normalizeStart;
            int schemaTableCount = asObject(schema.getSchema()).size();
            log.info("AI 查询生成成功：type={}, schemaTableCount={}, promptLength={}, promptBuildElapsedMs={}, aiCallElapsedMs={}, parseElapsedMs={}, normalizeElapsedMs={}, normalizedQuery={}",
                type, schemaTableCount, prompt.length(), promptBuildElapsed, aiCallElapsed, parseElapsed, normalizeElapsed, generatedQuery.getQuery());
            return generatedQuery;
        } catch (Exception exception) {
            log.warn("AI 生成查询失败：type={}, message={}", type, exception.getMessage());
            throw new BadRequestException("AI 生成查询失败，请稍后重试或改用手动模式");
        }
    }

    public String explainResult(String question, QueryExecutionResult result) {
        ChatClient chatClient = currentChatClient();
        if (chatClient == null) {
            log.info("AI 总结路径已跳过：当前 ChatClient 不可用，改用兜底摘要。type={}", result.getType());
            return fallbackExplainResult(question, result);
        }
        if (shouldSkipAiExplain(result)) {
            log.info("AI 总结路径已跳过：简单标量结果直接使用规则摘要。type={}, summary={}", result.getType(), result.getSummary());
            return fallbackExplainResult(question, result);
        }
        log.info("AI 总结路径已启用：provider={}, reasoningEffort={}, baseUrl={}, model={}, completionsPath={}, type={}",
            currentProviderName(), currentReasoningEffort(), currentBaseUrl(), currentModel(), currentCompletionsPath(), result.getType());
        String answer = chatClient.prompt()
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
        log.info("AI 总结内容已生成：type={}, answer={}", result.getType(), answer);
        return answer;
    }

    private boolean shouldSkipAiExplain(QueryExecutionResult result) {
        if (result == null || result.getRows() == null || result.getRows().size() != 1) {
            return false;
        }
        Map<String, Object> row = result.getRows().get(0);
        return row.size() == 1;
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
        MysqlQueryPlan plan = mysqlQueryAnalyzer.analyze(question, schema);
        String questionLower = question.toLowerCase(Locale.ROOT);
        String table = plan.preferredTable() != null ? plan.preferredTable() : resolveMysqlTable(question, schema);
        if (table == null) {
            throw new BadRequestException("当前未启用 AI，无法从问题中确定 MySQL 目标表，请改用 RAW 模式或在问题中明确表名。");
        }

        List<String> columns = extractMysqlColumns(schema.get(table));
        List<String> indexedColumns = extractMysqlIndexedColumns(schema.get(table));
        Integer limit = plan.limit() != null ? plan.limit() : extractLimit(question, question.contains("最近") ? 5 : 10);
        StringBuilder sql = new StringBuilder();

        GeneratedQuery quotaDetailQuery = fallbackMysqlQuotaDetailQuery(question, schema, table, columns, indexedColumns);
        if (quotaDetailQuery != null) {
            return quotaDetailQuery;
        }

        if (plan.intent() == MysqlQueryIntent.COUNT || isCountQuestion(questionLower)) {
            sql.append("SELECT COUNT(*) AS ");
            sql.append(isUserLikeName(table) ? "total_users" : "total_count");
            sql.append(" FROM ").append(table);
            String deleteFlag = findPreferredColumn(columns, indexedColumns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
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

        List<String> selectedColumns = preferredMysqlProjection(columns, indexedColumns);
        sql.append("SELECT ");
        sql.append(String.join(", ", selectedColumns));
        sql.append(" FROM ").append(table);

        List<String> conditions = new ArrayList<>();
        String deleteFlag = findPreferredColumn(columns, indexedColumns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
        if (deleteFlag != null) {
            conditions.add(deleteFlag + " = 0");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        if (plan.intent() == MysqlQueryIntent.LIST_RECENT || question.contains("最近") || question.contains("最新")) {
            String orderColumn = findPreferredColumn(columns, indexedColumns, List.of("create_time", "created_at", "gmt_create", "created_time", "update_time", "updated_at"));
            if (orderColumn != null) {
                sql.append(" ORDER BY ").append(orderColumn).append(" DESC");
            }
        } else if (plan.intent() == MysqlQueryIntent.TOP_N || question.contains("最高") || question.contains("最贵") || question.contains("最大")) {
            String orderColumn = findPreferredColumn(columns, indexedColumns, List.of("price", "sale_price", "amount", "sales", "sale_count", "score", "hot"));
            if (orderColumn != null) {
                sql.append(" ORDER BY ").append(orderColumn).append(" DESC");
            }
        }

        sql.append(" LIMIT ").append(limit);
        return GeneratedQuery.builder()
            .type(DatasourceType.MYSQL)
            .query(sql.toString())
            .reasoning("当前未启用 AI，已按归一化关键词、意图识别和表结构生成兜底 SQL。")
            .safetyNotes("仅生成单条只读 SELECT 查询。")
            .build();
    }

    private GeneratedQuery normalizeMysqlGeneratedQuery(String question,
                                                        DatasourceSchemaResponse schemaResponse,
                                                        GeneratedQuery generatedQuery) {
        if (generatedQuery.getType() != DatasourceType.MYSQL) {
            return generatedQuery;
        }
        Map<String, Object> schema = asObject(schemaResponse.getSchema());
        GeneratedQuery quotaDetailQuery = normalizeMysqlQuotaDetailQuery(question, schema, generatedQuery);
        if (quotaDetailQuery != null) {
            return quotaDetailQuery;
        }
        GeneratedQuery userCountQuery = normalizeMysqlUserCountQuery(question, schema, generatedQuery);
        logMysqlSoftDeleteExpectation(question, schema, userCountQuery);
        return userCountQuery;
    }

    private GeneratedQuery normalizeMysqlUserCountQuery(String question,
                                                        Map<String, Object> schema,
                                                        GeneratedQuery generatedQuery) {
        if (!shouldForcePrimaryUserCountQuery(question, schema)) {
            return generatedQuery;
        }
        String table = resolveMysqlTable(question, schema);
        if (table == null) {
            return generatedQuery;
        }
        List<String> columns = extractMysqlColumns(schema.get(table));
        List<String> indexedColumns = extractMysqlIndexedColumns(schema.get(table));
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total_users FROM ").append(table);
        String deleteFlag = findPreferredColumn(columns, indexedColumns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
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

    private GeneratedQuery normalizeMysqlQuotaDetailQuery(String question,
                                                          Map<String, Object> schema,
                                                          GeneratedQuery generatedQuery) {
        if (!isUserToolQuotaQuestion(question)) {
            return null;
        }
        String quotaTable = resolveMysqlQuotaTable(schema);
        if (quotaTable == null) {
            return null;
        }
        Integer userId = extractFirstNumber(question);
        if (userId == null) {
            return null;
        }
        List<String> columns = extractMysqlColumns(schema.get(quotaTable));
        List<String> indexedColumns = extractMysqlIndexedColumns(schema.get(quotaTable));
        String normalizedQuery = buildMysqlQuotaDetailSql(quotaTable, columns, indexedColumns, userId);
        if (normalizedQuery == null) {
            return null;
        }
        return GeneratedQuery.builder()
            .type(DatasourceType.MYSQL)
            .query(normalizedQuery)
            .reasoning(StrUtil.blankToDefault(generatedQuery.getReasoning(), "已自动纠正为用户工具配额明细查询。"))
            .safetyNotes(StrUtil.blankToDefault(generatedQuery.getSafetyNotes(), "仅生成单条只读 SELECT 查询。"))
            .build();
    }

    private void logMysqlSoftDeleteExpectation(String question,
                                               Map<String, Object> schema,
                                               GeneratedQuery generatedQuery) {
        if (generatedQuery.getType() != DatasourceType.MYSQL || StrUtil.isBlank(generatedQuery.getQuery())) {
            return;
        }
        String sql = generatedQuery.getQuery().trim();
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        if (!lowerSql.startsWith("select") || !shouldApplySoftDeleteCondition(question)) {
            log.info("MySQL 软删除条件检查已跳过：reason=not-select-or-question-opt-out, question={}, query={}", question, sql);
            return;
        }

        String table = extractSingleMysqlTable(sql, schema.keySet().stream().toList());
        if (table == null) {
            log.info("MySQL 软删除条件检查已跳过：reason=table-not-resolved, question={}, query={}", question, sql);
            return;
        }
        List<String> columns = extractMysqlColumns(schema.get(table));
        List<String> indexedColumns = extractMysqlIndexedColumns(schema.get(table));
        String deleteFlag = findPreferredColumn(columns, indexedColumns, List.of("delete_flag", "deleted", "is_deleted", "del_flag"));
        if (deleteFlag == null) {
            log.info("MySQL 软删除条件检查结果：table={}, deleteFlagPresent=false, query={}", table, sql);
            return;
        }
        boolean containsDeleteFlagCondition = lowerSql.contains(deleteFlag.toLowerCase(Locale.ROOT));
        log.info("MySQL 软删除条件检查结果：table={}, deleteFlag={}, deleteFlagIncluded={}, query={}",
            table, deleteFlag, containsDeleteFlagCondition, sql);
    }

    private GeneratedQuery fallbackMysqlQuotaDetailQuery(String question,
                                                         Map<String, Object> schema,
                                                         String table,
                                                         List<String> columns,
                                                         List<String> indexedColumns) {
        if (!isUserToolQuotaQuestion(question)) {
            return null;
        }
        String quotaTable = isQuotaTable(table) ? table : resolveMysqlQuotaTable(schema);
        if (quotaTable == null) {
            return null;
        }
        Integer userId = extractFirstNumber(question);
        if (userId == null) {
            return null;
        }
        List<String> quotaColumns = quotaTable.equals(table) ? columns : extractMysqlColumns(schema.get(quotaTable));
        List<String> quotaIndexedColumns = quotaTable.equals(table) ? indexedColumns : extractMysqlIndexedColumns(schema.get(quotaTable));
        String sql = buildMysqlQuotaDetailSql(quotaTable, quotaColumns, quotaIndexedColumns, userId);
        if (sql == null) {
            return null;
        }
        return GeneratedQuery.builder()
            .type(DatasourceType.MYSQL)
            .query(sql)
            .reasoning("当前未启用 AI，已识别为指定用户工具配额明细查询并优先命中用户工具配额表。")
            .safetyNotes("仅生成单条只读 SELECT 查询。")
            .build();
    }

    private String buildMysqlQuotaDetailSql(String table,
                                            List<String> columns,
                                            List<String> indexedColumns,
                                            int userId) {
        String userIdColumn = findPreferredColumn(columns, indexedColumns, List.of("user_id"));
        String toolIdColumn = findPreferredColumn(columns, indexedColumns, List.of("tool_id"));
        String remainingCountColumn = findPreferredColumn(columns, indexedColumns, List.of("remaining_count"));
        if (userIdColumn == null || toolIdColumn == null || remainingCountColumn == null) {
            return null;
        }
        return "SELECT %s, %s FROM %s WHERE %s = %d"
            .formatted(toolIdColumn, remainingCountColumn, table, userIdColumn, userId);
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
        } else if (isKafkaUnconsumedQuestion(question) && topic != null) {
            String consumerGroup = extractKafkaConsumerGroup(question, schema);
            if (consumerGroup == null) {
                throw new BadRequestException("查询 Kafka 未消费消息时必须明确指定 consumer group，例如：topic pay-success-topic 对 consumer group pay-success-group 还有多少条消息没被消费");
            }
            spec.put("operation", "COUNT_UNCONSUMED_MESSAGES");
            spec.put("topic", topic);
            spec.put("consumerGroup", consumerGroup);
        } else if (isKafkaMessageCountQuestion(question) && topic != null) {
            spec.put("operation", "COUNT_MESSAGES");
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
            .reasoning("当前未启用 AI，已按 topic 名、consumer group 和消息查询关键词生成只读 Kafka DSL。")
            .safetyNotes("Kafka 仅支持查看 topic 列表、topic 详情和统计消息，不允许猜测未指定的 consumer group。")
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

    private boolean isKafkaMessageCountQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        boolean mentionsMessage = question.contains("消息") || lower.contains("message") || lower.contains("messages");
        boolean mentionsCount = isCountQuestion(lower)
            || question.contains("一共")
            || question.contains("累计")
            || question.contains("总共有");
        boolean asksToReadContent = question.contains("最近")
            || question.contains("最新")
            || question.contains("内容")
            || question.contains("明细")
            || question.contains("查看消息")
            || lower.contains("latest");
        return mentionsMessage && mentionsCount && !asksToReadContent;
    }

    private boolean isKafkaUnconsumedQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return (question.contains("未消费")
            || question.contains("没被消费")
            || question.contains("消费积压")
            || lower.contains("unconsumed")
            || lower.contains("lag"))
            && (question.contains("消息") || lower.contains("message"));
    }

    private String extractKafkaConsumerGroup(String question, Map<String, Object> schema) {
        String markerValue = extractKeywordAfter(question, "consumer group");
        if (markerValue != null) {
            return normalizeKafkaConsumerGroupToken(markerValue, schema);
        }
        markerValue = extractKeywordAfter(question, "消费组");
        if (markerValue != null) {
            return normalizeKafkaConsumerGroupToken(markerValue, schema);
        }
        Matcher matcher = Pattern.compile("(?i)group\\s+([A-Za-z0-9._-]{3,})").matcher(question);
        if (matcher.find()) {
            return normalizeKafkaConsumerGroupToken(matcher.group(1), schema);
        }
        return null;
    }

    private String normalizeKafkaConsumerGroupToken(String rawValue, Map<String, Object> schema) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        String normalized = rawValue.trim();
        for (String separator : List.of(" 中", " 下", " 里", " 上", "中的", "下的", "里的", "上的", "还有", "一共", "总共", "多少", "没被消费", "未消费", "的消息", "消息")) {
            int index = normalized.indexOf(separator);
            if (index > 0) {
                normalized = normalized.substring(0, index).trim();
            }
        }
        normalized = normalized.replaceAll("[：:，。,；;、]+$", "").trim();
        if (normalized.isEmpty() || schema.containsKey(normalized)) {
            return null;
        }
        return normalized;
    }

    private boolean isUserToolQuotaQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        boolean mentionsUser = lower.contains("user") || question.contains("用户");
        boolean mentionsTool = lower.contains("tool") || question.contains("工具");
        boolean mentionsQuota = question.contains("可用次数")
            || question.contains("剩余次数")
            || question.contains("剩余可用次数")
            || question.contains("配额")
            || question.contains("额度")
            || lower.contains("quota")
            || lower.contains("remaining_count");
        return mentionsUser && mentionsTool && mentionsQuota;
    }

    private boolean shouldApplySoftDeleteCondition(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return !(question.contains("已删除")
            || question.contains("删除的数据")
            || question.contains("全部")
            || question.contains("所有")
            || question.contains("忽略删除")
            || lower.contains("deleted")
            || lower.contains("all"));
    }

    private boolean shouldForcePrimaryUserCountQuery(String question, Map<String, Object> schema) {
        if (isUserToolQuotaQuestion(question)) {
            return false;
        }
        String questionLower = question.toLowerCase(Locale.ROOT);
        if (!isCountQuestion(questionLower)) {
            return false;
        }
        if (isUserCourseOrOrderCountQuestion(question, questionLower)) {
            return false;
        }
        if (!(questionLower.contains("user") || question.contains("用户"))) {
            return false;
        }
        if (!(question.contains("多少用户")
            || question.contains("用户数量")
            || question.contains("总用户数")
            || question.contains("用户总数")
            || questionLower.contains("user count")
            || questionLower.contains("total users"))) {
            return false;
        }
        String explicitTable = resolveSchemaObjectName(question, schema.keySet().stream().toList());
        return explicitTable == null || isUserLikeName(explicitTable);
    }

    private boolean isUserCourseOrOrderCountQuestion(String question, String questionLower) {
        boolean mentionsUser = questionLower.contains("user") || question.contains("用户");
        boolean mentionsCourse = questionLower.contains("course") || question.contains("课程") || question.contains("专栏");
        boolean mentionsTransaction = question.contains("买")
            || question.contains("购买")
            || question.contains("下单")
            || question.contains("订单")
            || question.contains("学习")
            || question.contains("进度")
            || questionLower.contains("order")
            || questionLower.contains("study")
            || questionLower.contains("progress")
            || questionLower.contains("purchase")
            || questionLower.contains("buy");
        return mentionsUser && mentionsCourse && mentionsTransaction;
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

    private String extractSingleMysqlTable(String sql, List<String> tableNames) {
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        return tableNames.stream()
            .filter(name -> lowerSql.contains(("from " + name).toLowerCase(Locale.ROOT))
                || lowerSql.contains(("join " + name).toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparingInt(String::length).reversed())
            .findFirst()
            .orElse(null);
    }

    private int userTablePriority(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        if (isQuotaTable(lower)) {
            return 5;
        }
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

    private boolean isQuotaTable(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        return "osh_user_tool_quota".equals(lower) || "osh_user_tool_quotas".equals(lower);
    }

    private String resolveMysqlQuotaTable(Map<String, Object> schema) {
        return schema.keySet().stream()
            .filter(this::isQuotaTable)
            .findFirst()
            .orElse(null);
    }

    private List<String> extractMysqlColumns(Object rawColumns) {
        Object value = rawColumns;
        if (rawColumns instanceof Map<?, ?>) {
            value = asObject(rawColumns).getOrDefault("columns", List.of());
        }
        if (!(value instanceof List<?> columns)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object column : columns) {
            result.add(Objects.toString(asObject(column).get("columnName"), ""));
        }
        return result.stream().filter(StrUtil::isNotBlank).toList();
    }

    private List<String> extractMysqlIndexedColumns(Object rawColumns) {
        Object value = rawColumns;
        if (rawColumns instanceof Map<?, ?>) {
            value = asObject(rawColumns).getOrDefault("indexes", List.of());
        }
        if (!(value instanceof List<?> indexes)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object index : indexes) {
            String columnName = Objects.toString(asObject(index).get("columnName"), "");
            if (StrUtil.isNotBlank(columnName) && !result.contains(columnName)) {
                result.add(columnName);
            }
        }
        return result;
    }

    private List<String> preferredMysqlProjection(List<String> columns, List<String> indexedColumns) {
        List<String> projection = new ArrayList<>();
        for (String candidate : List.of("id", "user_id", "title", "name", "username", "price", "status", "create_time", "created_at", "update_time")) {
            String column = findPreferredColumn(columns, indexedColumns, List.of(candidate));
            if (column != null && !projection.contains(column)) {
                projection.add(column);
            }
        }
        for (String indexedColumn : indexedColumns) {
            String column = findColumn(columns, List.of(indexedColumn));
            if (column != null && !projection.contains(column)) {
                projection.add(column);
            }
            if (projection.size() >= 8) {
                break;
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

    private String findPreferredColumn(List<String> columns, List<String> indexedColumns, List<String> candidates) {
        for (String candidate : candidates) {
            for (String indexedColumn : indexedColumns) {
                if (indexedColumn.equalsIgnoreCase(candidate)) {
                    String matched = findColumn(columns, List.of(indexedColumn));
                    if (matched != null) {
                        return matched;
                    }
                }
            }
        }
        for (String candidate : candidates) {
            for (String indexedColumn : indexedColumns) {
                if (indexedColumn.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))) {
                    String matched = findColumn(columns, List.of(indexedColumn));
                    if (matched != null) {
                        return matched;
                    }
                }
            }
        }
        return findColumn(columns, candidates);
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

    private String appendMysqlCondition(String sql, String condition) {
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        int orderByIndex = lowerSql.indexOf(" order by ");
        int groupByIndex = lowerSql.indexOf(" group by ");
        int limitIndex = lowerSql.indexOf(" limit ");
        int insertIndex = sql.length();
        for (int candidate : List.of(orderByIndex, groupByIndex, limitIndex)) {
            if (candidate >= 0 && candidate < insertIndex) {
                insertIndex = candidate;
            }
        }
        String head = sql.substring(0, insertIndex).trim();
        String tail = sql.substring(insertIndex);
        if (lowerSql.contains(" where ")) {
            return head + " AND " + condition + tail;
        }
        return head + " WHERE " + condition + tail;
    }

    private Integer extractFirstNumber(String question) {
        Matcher matcher = NUMBER_PATTERN.matcher(question);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
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
        } catch (Exception exception) {
            int firstBrace = normalized.indexOf('{');
            int lastBrace = normalized.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                try {
                    return JsonUtils.fromJson(normalized.substring(firstBrace, lastBrace + 1), PromptOutput.class);
                } catch (Exception ignored) {
                    // continue to more tolerant extraction below
                }
            }
            if (normalized.contains("\"query\"")) {
                try {
                    return JsonUtils.fromJson(extractLikelyJsonObject(normalized), PromptOutput.class);
                } catch (Exception ignored) {
                    // continue to field-based extraction below
                }
            }
            PromptOutput extracted = extractPromptOutputFields(normalized);
            if (extracted != null) {
                return extracted;
            }
            log.warn("AI 查询结果 JSON 解析失败：contentPreview={}, message={}",
                previewContent(normalized), exception.getMessage());
            throw exception;
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

    private ChatClient currentChatClient() {
        return aiChatClientFactory == null ? null : aiChatClientFactory.currentClient();
    }

    private String currentProviderName() {
        return aiChatClientFactory == null ? "unknown" : aiChatClientFactory.currentProvider().name();
    }

    private String currentReasoningEffort() {
        return aiChatClientFactory == null
            ? "(unknown)"
            : StrUtil.blankToDefault(aiChatClientFactory.currentReasoningEffort(), "(empty)");
    }

    private String currentBaseUrl() {
        return aiChatClientFactory == null
            ? "(unknown)"
            : StrUtil.blankToDefault(aiChatClientFactory.currentBaseUrl(), "(empty)");
    }

    private String currentModel() {
        return aiChatClientFactory == null
            ? "(unknown)"
            : StrUtil.blankToDefault(aiChatClientFactory.currentModel(), "(empty)");
    }

    private String currentCompletionsPath() {
        return aiChatClientFactory == null
            ? "(unknown)"
            : StrUtil.blankToDefault(aiChatClientFactory.currentCompletionsPath(), "(empty)");
    }

    private String buildGenerateQueryPrompt(DatasourceType type, String question, DatasourceSchemaResponse schema) {
        return """
            %s

            %s

            数据源类型：%s
            用户问题：%s
            数据结构摘要：
            %s
            """.formatted(
            GENERATE_QUERY_COMMON_RULES.trim(),
            rulesFor(type).trim(),
            type.name(),
            question,
            JsonUtils.toJson(schema.getSchema())
        );
    }

    private String rulesFor(DatasourceType type) {
        return switch (type) {
            case MYSQL -> GENERATE_QUERY_MYSQL_RULES;
            case REDIS -> GENERATE_QUERY_REDIS_RULES;
            case ELASTICSEARCH -> GENERATE_QUERY_ES_RULES;
            case KAFKA -> GENERATE_QUERY_KAFKA_RULES;
        };
    }

    private String extractLikelyJsonObject(String content) {
        Map<String, Object> wrapper = JsonUtils.fromJson(content, new TypeReference<>() {
        });
        return JsonUtils.toJson(wrapper);
    }

    private PromptOutput extractPromptOutputFields(String content) {
        String query = extractJsonStringField(content, "query");
        if (StrUtil.isBlank(query)) {
            return null;
        }
        String reasoning = extractJsonStringField(content, "reasoning");
        String safetyNotes = extractJsonStringField(content, "safetyNotes");
        PromptOutput output = new PromptOutput();
        output.setQuery(query);
        output.setReasoning(reasoning == null ? "" : reasoning);
        output.setSafetyNotes(safetyNotes == null ? "" : safetyNotes);
        return output;
    }

    private String extractJsonStringField(String content, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL)
            .matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String rawValue = matcher.group(1);
        return rawValue
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    private String previewContent(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 300) {
            return compact;
        }
        return compact.substring(0, 300) + "...";
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
        if (type == DatasourceType.MYSQL) {
            GeneratedQuery normalizedQuery = normalizeMysqlGeneratedQuery(question, schema, GeneratedQuery.builder()
                .type(type)
                .query(query)
                .build());
            query = normalizedQuery.getQuery();
        }
        if (type == DatasourceType.KAFKA) {
            query = normalizeKafkaGeneratedQuery(question, query);
        }
        return query;
    }

    private String normalizeKafkaGeneratedQuery(String question, String query) {
        Map<String, Object> payload = JsonUtils.fromJson(query, new TypeReference<>() {
        });
        if (payload == null || payload.isEmpty()) {
            return query;
        }
        String operation = Objects.toString(payload.get("operation"), "").trim().toUpperCase(Locale.ROOT);
        if ("READ_MESSAGES".equals(operation) && isKafkaUnconsumedQuestion(question)) {
            String topic = Objects.toString(payload.get("topic"), null);
            String consumerGroup = Objects.toString(payload.get("consumerGroup"), null);
            if (StrUtil.isBlank(consumerGroup)) {
                throw new BadRequestException("查询 Kafka 未消费消息时必须明确指定 consumer group，例如：topic pay-success-topic 对 consumer group pay-success-group 还有多少条消息没被消费");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("operation", "COUNT_UNCONSUMED_MESSAGES");
            normalized.put("topic", topic);
            normalized.put("consumerGroup", consumerGroup);
            return JsonUtils.toJson(normalized);
        }
        if ("COUNT_MESSAGES".equals(operation) && isKafkaUnconsumedQuestion(question)) {
            String topic = Objects.toString(payload.get("topic"), null);
            String consumerGroup = Objects.toString(payload.get("consumerGroup"), null);
            if (StrUtil.isBlank(consumerGroup)) {
                throw new BadRequestException("查询 Kafka 未消费消息时必须明确指定 consumer group，例如：topic pay-success-topic 对 consumer group pay-success-group 还有多少条消息没被消费");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("operation", "COUNT_UNCONSUMED_MESSAGES");
            normalized.put("topic", topic);
            normalized.put("consumerGroup", consumerGroup);
            return JsonUtils.toJson(normalized);
        }
        if ("READ_MESSAGES".equals(operation) && isKafkaMessageCountQuestion(question)) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("operation", "COUNT_MESSAGES");
            if (payload.get("topic") != null) {
                normalized.put("topic", payload.get("topic"));
            }
            return JsonUtils.toJson(normalized);
        }
        return query;
    }
}
