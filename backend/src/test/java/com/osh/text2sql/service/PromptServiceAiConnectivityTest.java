package com.osh.text2sql.service;

import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 用于手工验证 AI 连通性，不参与日常自动测试。
 */
@SpringBootTest
class PromptServiceAiConnectivityTest {

    @Autowired
    private PromptService promptService;

    @Test
    void shouldCallRealAiForMysqlQuestion() {
        GeneratedQuery query = promptService.generateQuery(
            DatasourceType.MYSQL,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION_MYSQL", "查询可用工具的数量"),
            mysqlSchema()
        );
        assertAndPrint("MYSQL", query);
    }

    @Test
    void shouldCallRealAiForRedisQuestion() {
        GeneratedQuery query = promptService.generateQuery(
            DatasourceType.REDIS,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION_REDIS", "列出当前 Redis 数据库前 20 个 key"),
            redisSchema()
        );
        assertAndPrint("REDIS", query);
    }

    @Test
    void shouldCallRealAiForElasticsearchQuestion() {
        GeneratedQuery query = promptService.generateQuery(
            DatasourceType.ELASTICSEARCH,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION_ES", "查询 osh_course_index 中销量最高的 5 个课程"),
            elasticsearchSchema()
        );
        assertAndPrint("ELASTICSEARCH", query);
    }

    @Test
    void shouldCallRealAiForKafkaQuestion() {
        GeneratedQuery query = promptService.generateQuery(
            DatasourceType.KAFKA,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION_KAFKA", "查看 user-action topic 最近 10 条消息"),
            kafkaSchema()
        );
        assertAndPrint("KAFKA", query);
    }

    @Test
    void shouldCallRealAiForHbaseQuestion() {
        GeneratedQuery query = promptService.generateQuery(
            DatasourceType.HBASE,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION_HBASE", "查看 user_profile 表结构"),
            hbaseSchema()
        );
        assertAndPrint("HBASE", query);
    }

    @Test
    void shouldCallRealAiForHbaseQuestion_1() {
        GeneratedQuery query = promptService.generateQuery(
                DatasourceType.HBASE,
                System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION_HBASE", "查看 user_profile 表前10条数据"),
                hbaseSchema()
        );
        assertAndPrint("HBASE", query);
    }

    private void assertAndPrint(String type, GeneratedQuery query) {
        Assertions.assertNotNull(query);
        Assertions.assertNotNull(query.getQuery());
        Assertions.assertFalse(query.getQuery().isBlank());
        System.out.println("AI test type = " + type);
        System.out.println("AI generated query = " + query.getQuery());
        System.out.println("AI reasoning = " + query.getReasoning());
        System.out.println("AI safetyNotes = " + query.getSafetyNotes());
    }

    private DatasourceSchemaResponse mysqlSchema() {
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .schema(Map.of(
                "osh_tool", Map.of(
                    "columns", List.of(
                        Map.of("columnName", "id", "dataType", "bigint", "columnComment", "主键"),
                        Map.of("columnName", "tool_name", "dataType", "varchar", "columnComment", "工具名称"),
                        Map.of("columnName", "status", "dataType", "int", "columnComment", "状态 4-已发布"),
                        Map.of("columnName", "delete_flag", "dataType", "tinyint", "columnComment", "是否删除 0-未删除 1-已删除")
                    ),
                    "indexes", List.of(
                        Map.of("indexName", "PRIMARY", "columnName", "id"),
                        Map.of("indexName", "idx_status", "columnName", "status"),
                        Map.of("indexName", "idx_delete_flag", "columnName", "delete_flag")
                    )
                )
            ))
            .build();
    }

    private DatasourceSchemaResponse redisSchema() {
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.REDIS)
            .schema(Map.of(
                "user:1001:profile", Map.of("type", "string", "ttl", -1),
                "tool:1001:counter", Map.of("type", "hash", "ttl", 3600)
            ))
            .build();
    }

    private DatasourceSchemaResponse elasticsearchSchema() {
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.ELASTICSEARCH)
            .schema(Map.of(
                "osh_course_index", Map.of(
                    "docsCount", 100,
                    "status", "open",
                    "fields", List.of("title", "sale_count", "status")
                )
            ))
            .build();
    }

    private DatasourceSchemaResponse kafkaSchema() {
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.KAFKA)
            .schema(Map.of(
                "user-action", Map.of("partitions", 3),
                "osh-kafka-key-status-test", Map.of("partitions", 1)
            ))
            .build();
    }

    private DatasourceSchemaResponse hbaseSchema() {
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.HBASE)
            .schema(Map.of(
                "user_profile", Map.of(
                    "namespace", "default",
                    "columnFamilies", List.of(Map.of("family", "info"))
                )
            ))
            .build();
    }
}
