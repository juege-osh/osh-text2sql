package com.osh.text2sql.service;

import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
