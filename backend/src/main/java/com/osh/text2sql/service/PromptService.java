package com.osh.text2sql.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import com.osh.text2sql.dto.PromptOutput;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.util.JsonUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PromptService {

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
        6. 查询必须尽量精简且可执行。

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

    public PromptService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public GeneratedQuery generateQuery(DatasourceType type, String question, DatasourceSchemaResponse schema) {
        String content = chatClient.prompt()
            .system("你必须只输出合法 JSON 对象，不要输出 markdown，不要输出解释性前后缀。")
            .user(GENERATE_QUERY_TEMPLATE.render(Map.of(
                "type", type.name(),
                "question", question,
                "schema", JsonUtils.toJson(schema.getSchema())
            )))
            .call()
            .content();
        PromptOutput output = parsePromptOutput(content);
        String query = stringifyQuery(output.getQuery());
        if (query != null) {
            query = query.trim();
            if (type == DatasourceType.MYSQL && query.endsWith(";")) {
                query = query.substring(0, query.length() - 1);
            }
        }
        return GeneratedQuery.builder()
            .type(type)
            .query(query)
            .reasoning(output.getReasoning())
            .safetyNotes(output.getSafetyNotes())
            .build();
    }

    public String explainResult(String question, QueryExecutionResult result) {
        return chatClient.prompt()
            .system("只用中文给出简洁可信的结论，不要捏造不存在的数据。")
            .user(EXPLAIN_RESULT_TEMPLATE.render(Map.of(
                "question", question,
                "type", result.getType().name(),
                "query", result.getExecutedQuery(),
                "result", JsonUtils.toJson(Map.of(
                    "summary", result.getSummary(),
                    "columns", result.getColumns(),
                    "rows", result.getRows(),
                    "total", result.getTotal()
                ))
            )))
            .call()
            .content();
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
}
