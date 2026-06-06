package com.osh.text2sql.service;

import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.QueryMode;
import com.osh.text2sql.dto.QueryRequest;
import com.osh.text2sql.dto.QueryResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真实 AI + 真实数据源联调场景测试。
 * 运行这些测试前，请先配置好对应环境变量，并确保默认数据源可访问。
 */
@SpringBootTest
class Text2QueryServiceAiScenarioTest {

    @Autowired
    private Text2QueryService text2QueryService;

    @Test
    void shouldRunMysqlCommonScenario() {
        QueryResponse response = text2QueryService.query(buildRequest(
            DatasourceType.MYSQL,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_CASE_MYSQL", "查询可用工具的数量")
        ));
        assertAndPrint("MYSQL", response);
    }

    @Test
    void shouldRunRedisCommonScenario() {
        QueryResponse response = text2QueryService.query(buildRequest(
            DatasourceType.REDIS,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_CASE_REDIS", "列出当前 Redis 数据库前 20 个 key")
        ));
        assertAndPrint("REDIS", response);
    }

    @Test
    void shouldRunElasticsearchCommonScenario() {
        QueryResponse response = text2QueryService.query(buildRequest(
            DatasourceType.ELASTICSEARCH,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_CASE_ES", "查询 osh_course_index 中销量最高的 5 个课程")
        ));
        assertAndPrint("ELASTICSEARCH", response);
    }

    @Test
    void shouldRunKafkaCommonScenario() {
        QueryResponse response = text2QueryService.query(buildRequest(
            DatasourceType.KAFKA,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_CASE_KAFKA", "查看 user-action topic 最近 10 条消息")
        ));
        assertAndPrint("KAFKA", response);
    }

    @Test
    void shouldRunKafkaConsumerGroupKeyScenario() {
        QueryResponse response = text2QueryService.query(buildRequest(
            DatasourceType.KAFKA,
            System.getenv().getOrDefault(
                "OSH_TEXT2SQL_AI_CASE_KAFKA_KEY",
                "查询 topic osh-kafka-key-status-test 对 consumer group osh-kafka-key-status-group 中 key 为 tool-1001 的消息消费情况"
            )
        ));
        assertAndPrint("KAFKA_KEY", response);
    }

    @Test
    void shouldRunHbaseCommonScenario() {
        QueryResponse response = text2QueryService.query(buildRequest(
            DatasourceType.HBASE,
            System.getenv().getOrDefault("OSH_TEXT2SQL_AI_CASE_HBASE", "列出当前 HBase 命名空间下的表")
        ));
        assertAndPrint("HBASE", response);
    }

    private QueryRequest buildRequest(DatasourceType type, String question) {
        QueryRequest request = new QueryRequest();
        request.setType(type);
        request.setMode(QueryMode.AUTO);
        request.setQuestion(question);
        return request;
    }

    private void assertAndPrint(String type, QueryResponse response) {
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getGeneratedQuery());
        Assertions.assertNotNull(response.getGeneratedQuery().getQuery());
        Assertions.assertFalse(response.getGeneratedQuery().getQuery().isBlank());
        Assertions.assertNotNull(response.getResult());
        Assertions.assertNotNull(response.getAnswer());

        System.out.println("========== AI SCENARIO " + type + " ==========");
        System.out.println("Generated Query: " + response.getGeneratedQuery().getQuery());
        System.out.println("Reasoning: " + response.getGeneratedQuery().getReasoning());
        System.out.println("Safety Notes: " + response.getGeneratedQuery().getSafetyNotes());
        System.out.println("Result Summary: " + response.getResult().getSummary());
        System.out.println("Result Rows: " + response.getResult().getRows());
        System.out.println("Answer: " + response.getAnswer());
        System.out.println("==============================================");
    }
}
