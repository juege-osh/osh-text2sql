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

        GeneratedQuery query = promptService.generateQuery(DatasourceType.MYSQL, "统计总共有多少个用户", schema);

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

        GeneratedQuery query = promptService.generateQuery(DatasourceType.MYSQL, "用户id23的用户有分别有多少工具的可用次数", schema);

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
