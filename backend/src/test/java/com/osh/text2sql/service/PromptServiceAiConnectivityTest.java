package com.osh.text2sql.service;

import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 用于手工验证 AI 连通性，不参与日常自动测试。
 */
@Disabled("手工联调 AI 连通性时再打开运行")
@SpringBootTest
class PromptServiceAiConnectivityTest {

    @Autowired
    private PromptService promptService;

    @Test
    void shouldCallRealAiForMysqlQuestion() {
        DatasourceSchemaResponse schema = DatasourceSchemaResponse.builder()
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

        String question = System.getenv().getOrDefault("OSH_TEXT2SQL_AI_TEST_QUESTION", "查询可用工具的数量");
        GeneratedQuery query = promptService.generateQuery(DatasourceType.MYSQL, question, schema);

        System.out.println("AI test question = " + question);
        System.out.println("AI generated query = " + query.getQuery());
        System.out.println("AI reasoning = " + query.getReasoning());
        System.out.println("AI safetyNotes = " + query.getSafetyNotes());
    }
}
