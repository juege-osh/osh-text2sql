package com.osh.text2sql.service;

import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import com.osh.text2sql.dto.PromptOutput;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.util.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

class PromptServiceTest {

    @Test
    void shouldPreferPrimaryUserTableForUserCountFallback() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .schema(Map.of(
                "osh_group_user_initiated", List.of(
                    Map.of("columnName", "user_id"),
                    Map.of("columnName", "delete_flag")
                ),
                "osh_user", List.of(
                    Map.of("columnName", "id"),
                    Map.of("columnName", "delete_flag")
                ),
                "sys_user", List.of(
                    Map.of("columnName", "user_id"),
                    Map.of("columnName", "del_flag")
                )
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.MYSQL, "统计总共有多少个用户", schema);

        Assertions.assertEquals("SELECT COUNT(*) AS total_users FROM osh_user WHERE delete_flag = 0", query.getQuery());
    }

    @Test
    void shouldGenerateUserToolQuotaDetailQuery() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .schema(Map.of(
                "osh_user", List.of(
                    Map.of("columnName", "id"),
                    Map.of("columnName", "delete_flag")
                ),
                "osh_tool", List.of(
                    Map.of("columnName", "id"),
                    Map.of("columnName", "delete_flag")
                ),
                "osh_user_tool_quota", Map.of(
                    "columns", List.of(
                        Map.of("columnName", "user_id"),
                        Map.of("columnName", "tool_id"),
                        Map.of("columnName", "remaining_count")
                    ),
                    "indexes", List.of(
                        Map.of("indexName", "idx_user_remaining", "columnName", "user_id"),
                        Map.of("indexName", "uk_user_tool", "columnName", "tool_id")
                    )
                )
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.MYSQL, "用户id23的用户有分别有多少工具的可用次数", schema);

        Assertions.assertEquals("SELECT tool_id, remaining_count FROM osh_user_tool_quota WHERE user_id = 23", query.getQuery());
    }

    @Test
    void shouldGenerateKafkaCountMessagesDslForTopicMessageCountQuestion() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.KAFKA)
            .schema(Map.of(
                "pay-success-topic", Map.of("partitions", 1),
                "user-action", Map.of("partitions", 3)
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.KAFKA, "列出当前 Kafka 集群的pay-success-topic 一共有多少条消息", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("COUNT_MESSAGES", dsl.get("operation"));
        Assertions.assertEquals("pay-success-topic", dsl.get("topic"));
    }

    @Test
    void shouldGenerateKafkaUnconsumedDslWhenConsumerGroupProvided() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.KAFKA)
            .schema(Map.of(
                "pay-success-topic", Map.of("partitions", 1),
                "user-action", Map.of("partitions", 3)
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.KAFKA, "topic pay-success-topic 对 consumer group pay-success-group 还有多少条消息没被消费", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("COUNT_UNCONSUMED_MESSAGES", dsl.get("operation"));
        Assertions.assertEquals("pay-success-topic", dsl.get("topic"));
        Assertions.assertEquals("pay-success-group", dsl.get("consumerGroup"));
    }

    @Test
    void shouldGenerateKafkaUnconsumedDslWithKeyFilter() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.KAFKA)
            .schema(Map.of(
                "pay-success-topic", Map.of("partitions", 1)
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(
            promptService,
            DatasourceType.KAFKA,
            "topic pay-success-topic 对 consumer group pay-success-group 中 key 为 tool-1001 的消息还有多少条没被消费",
            schema
        );
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("COUNT_UNCONSUMED_MESSAGES", dsl.get("operation"));
        Assertions.assertEquals("pay-success-topic", dsl.get("topic"));
        Assertions.assertEquals("pay-success-group", dsl.get("consumerGroup"));
        Assertions.assertEquals("tool-1001", dsl.get("keyContains"));
    }

    @Test
    void shouldTrimChineseSuffixWhenExtractingKafkaConsumerGroup() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.KAFKA)
            .schema(Map.of(
                "osh.course.index", Map.of("partitions", 1),
                "pay-success-topic", Map.of("partitions", 1)
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.KAFKA, "topic: osh.course.index 下的消费者组backstage-course-index-flink 中还有多少个消息没被消费", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("COUNT_UNCONSUMED_MESSAGES", dsl.get("operation"));
        Assertions.assertEquals("osh.course.index", dsl.get("topic"));
        Assertions.assertEquals("backstage-course-index-flink", dsl.get("consumerGroup"));
    }

    @Test
    void shouldKeepAiQueryUnchangedWhenDeleteFlagIsMissingFromGeneratedSql() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .schema(Map.of(
                "osh_tool", Map.of(
                    "columns", List.of(
                        Map.of("columnName", "id"),
                        Map.of("columnName", "status"),
                        Map.of("columnName", "delete_flag")
                    ),
                    "indexes", List.of(
                        Map.of("indexName", "idx_status", "columnName", "status"),
                        Map.of("indexName", "idx_delete_flag", "columnName", "delete_flag")
                    )
                )
            ))
            .build();

        GeneratedQuery normalized = invokeNormalizeMysqlGeneratedQuery(
            promptService,
            "有多少可用的工具",
            schema,
            GeneratedQuery.builder()
                .type(DatasourceType.MYSQL)
                .query("SELECT COUNT(*) AS available_tool_count FROM osh_tool WHERE status = 4")
                .reasoning("AI 生成结果")
                .safetyNotes("仅生成只读查询")
                .build()
        );

        Assertions.assertEquals("SELECT COUNT(*) AS available_tool_count FROM osh_tool WHERE status = 4", normalized.getQuery());
    }

    @Test
    void shouldKeepNormalizedMysqlQueryStringUnchangedWhenDeleteFlagIsMissingFromGeneratedSql() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .schema(Map.of(
                "osh_tool", Map.of(
                    "columns", List.of(
                        Map.of("columnName", "id"),
                        Map.of("columnName", "status"),
                        Map.of("columnName", "delete_flag")
                    ),
                    "indexes", List.of(
                        Map.of("indexName", "idx_status", "columnName", "status"),
                        Map.of("indexName", "idx_delete_flag", "columnName", "delete_flag")
                    )
                )
            ))
            .build();

        String normalized = invokeNormalizeGeneratedQuery(
            promptService,
            DatasourceType.MYSQL,
            "统计可用工具数量",
            schema,
            "SELECT COUNT(*) AS available_tool_count FROM osh_tool WHERE status = 4"
        );

        Assertions.assertEquals("SELECT COUNT(*) AS available_tool_count FROM osh_tool WHERE status = 4", normalized);
    }

    @Test
    void shouldParsePromptOutputWhenJsonIsWrappedByExtraText() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);

        PromptOutput output = invokeParsePromptOutput(promptService, """
            下面是结果：
            {
              "query": "SELECT COUNT(*) AS available_tool_count FROM osh_tool WHERE status = 4 AND delete_flag = 0",
              "reasoning": "命中工具主表",
              "safetyNotes": "只读查询"
            }
            请查收。
            """);

        Assertions.assertEquals("SELECT COUNT(*) AS available_tool_count FROM osh_tool WHERE status = 4 AND delete_flag = 0", output.getQuery());
        Assertions.assertEquals("命中工具主表", output.getReasoning());
        Assertions.assertEquals("只读查询", output.getSafetyNotes());
    }

    @Test
    void shouldUseFallbackExplainForSingleValueResult() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        QueryExecutionResult result = QueryExecutionResult.builder()
            .type(DatasourceType.MYSQL)
            .summary("查询结果为 4")
            .rows(List.of(Map.of("available_tool_count", 4)))
            .build();

        String answer = promptService.explainResult("查询可用工具的数量", result);

        Assertions.assertEquals("根据查询结果，available_tool_count 为 4。", answer);
    }

    @Test
    void shouldGenerateHbaseScanDslForFallbackQuery() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.HBASE)
            .schema(Map.of(
                "user_profile", Map.of(
                    "namespace", "default",
                    "columnFamilies", List.of(Map.of("family", "info"))
                )
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.HBASE, "查看 user_profile 表前 10 行数据", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("SCAN_ROWS", dsl.get("operation"));
        Assertions.assertEquals("user_profile", dsl.get("table"));
    }

    @Test
    void shouldGenerateMysqlRecentListQueryForFeedbackTable() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .schema(Map.of(
                "assistant_feedback", Map.of(
                    "columns", List.of(
                        Map.of("columnName", "id"),
                        Map.of("columnName", "title"),
                        Map.of("columnName", "create_time"),
                        Map.of("columnName", "delete_flag")
                    ),
                    "indexes", List.of(
                        Map.of("indexName", "idx_create_time", "columnName", "create_time")
                    )
                )
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.MYSQL, "查询 assistant_feedback 表最近创建的 5 条反馈工单", schema);

        Assertions.assertTrue(query.getQuery().contains("FROM assistant_feedback"));
        Assertions.assertTrue(query.getQuery().contains("delete_flag = 0"));
        Assertions.assertTrue(query.getQuery().contains("ORDER BY create_time DESC"));
        Assertions.assertTrue(query.getQuery().contains("LIMIT 5"));
    }

    @Test
    void shouldGenerateRedisScanCommandForListKeysQuestion() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.REDIS)
            .schema(Map.of())
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.REDIS, "列出当前 Redis 数据库前 20 个 key", schema);

        Assertions.assertEquals("SCAN 0", query.getQuery());
    }

    @Test
    void shouldGenerateElasticsearchTopNQuery() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.ELASTICSEARCH)
            .schema(Map.of(
                "osh_course_index", Map.of(
                    "fields", List.of("title", "sale_count", "status")
                )
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.ELASTICSEARCH, "查询 osh_course_index 中销量最高的 5 个课程", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("osh_course_index", dsl.get("_index"));
        Assertions.assertEquals(5, dsl.get("size"));
        Assertions.assertTrue(query.getQuery().contains("sale_count"));
    }

    @Test
    void shouldGenerateKafkaReadMessagesDslForLatestMessagesQuestion() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.KAFKA)
            .schema(Map.of(
                "user-action", Map.of("partitions", 3)
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.KAFKA, "查看 user-action topic 最近 10 条消息", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("READ_MESSAGES", dsl.get("operation"));
        Assertions.assertEquals("user-action", dsl.get("topic"));
        Assertions.assertEquals(10, dsl.get("limit"));
        Assertions.assertEquals("LATEST", dsl.get("from"));
    }

    @Test
    void shouldGenerateHbaseDescribeTableDsl() {
        PromptService promptService = new PromptService((org.springframework.ai.chat.client.ChatClient) null);
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
            .type(DatasourceType.HBASE)
            .schema(Map.of(
                "user_profile", Map.of(
                    "namespace", "default",
                    "columnFamilies", List.of(Map.of("family", "info"))
                )
            ))
            .build();

        GeneratedQuery query = invokeFallbackGenerateQuery(promptService, DatasourceType.HBASE, "查看 user_profile 表结构", schema);
        Map<String, Object> dsl = JsonUtils.fromJson(query.getQuery(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });

        Assertions.assertEquals("DESCRIBE_TABLE", dsl.get("operation"));
        Assertions.assertEquals("user_profile", dsl.get("table"));
    }

    private GeneratedQuery invokeFallbackGenerateQuery(PromptService promptService,
                                                       DatasourceType type,
                                                       String question,
                                                       DatasourceSchemaResponse schema) {
        try {
            Method method = PromptService.class.getDeclaredMethod("fallbackGenerateQuery", DatasourceType.class, String.class, DatasourceSchemaResponse.class);
            method.setAccessible(true);
            return (GeneratedQuery) method.invoke(promptService, type, question, schema);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private GeneratedQuery invokeNormalizeMysqlGeneratedQuery(PromptService promptService,
                                                             String question,
                                                             DatasourceSchemaResponse schema,
                                                             GeneratedQuery generatedQuery) {
        try {
            Method method = PromptService.class.getDeclaredMethod("normalizeMysqlGeneratedQuery", String.class, DatasourceSchemaResponse.class, GeneratedQuery.class);
            method.setAccessible(true);
            return (GeneratedQuery) method.invoke(promptService, question, schema, generatedQuery);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private String invokeNormalizeGeneratedQuery(PromptService promptService,
                                                 DatasourceType type,
                                                 String question,
                                                 DatasourceSchemaResponse schema,
                                                 Object rawQuery) {
        try {
            Method method = PromptService.class.getDeclaredMethod("normalizeGeneratedQuery", DatasourceType.class, String.class, DatasourceSchemaResponse.class, Object.class);
            method.setAccessible(true);
            return (String) method.invoke(promptService, type, question, schema, rawQuery);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private PromptOutput invokeParsePromptOutput(PromptService promptService, String content) {
        try {
            Method method = PromptService.class.getDeclaredMethod("parsePromptOutput", String.class);
            method.setAccessible(true);
            return (PromptOutput) method.invoke(promptService, content);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
