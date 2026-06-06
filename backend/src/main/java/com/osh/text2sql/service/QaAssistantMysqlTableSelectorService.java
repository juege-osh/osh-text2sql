package com.osh.text2sql.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.MysqlTableSelectionResult;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 QA assistant 的 MySQL 选表服务
 */
@Service
public class QaAssistantMysqlTableSelectorService {

    private static final Logger log = LoggerFactory.getLogger(QaAssistantMysqlTableSelectorService.class);
    private static final String STREAM_END = "[DONE]";

    private final Text2SqlProperties properties;

    public QaAssistantMysqlTableSelectorService(Text2SqlProperties properties) {
        this.properties = properties;
    }

    public MysqlTableSelectionResult selectTables(ConnectionProfile profile, String question) {
        Text2SqlProperties.QaAssistantProperties qaConfig = properties.getMysqlTableSelector().getQaAssistant();
        validateConfig(qaConfig);
        String qaBaseUrl = normalizeBaseUrl(qaConfig.getBaseUrl());
        String rawResponse = invokeChatAndCollect(
            qaBaseUrl,
            qaConfig.getAppId(),
            qaConfig.getAppKey(),
            qaConfig.getChatId(),
            buildUserInput(profile, question)
        );
        MysqlTableSelectionResult result = parseSelectionResult(rawResponse);
        log.info("QA assistant 选表完成：database={}, selectedTables={}, reason={}",
            profile.getDatabase(), result.getTables(), result.getReason());
        return result;
    }

    private void validateConfig(Text2SqlProperties.QaAssistantProperties qaConfig) {
        if (!qaConfig.isEnabled()) {
            throw new BadRequestException("QA assistant 选表功能未启用");
        }
        if (qaConfig.getAppId() == null || qaConfig.getAppId() <= 0) {
            throw new BadRequestException("QA assistant appId 未配置");
        }
        if (qaConfig.getAppKey() == null || qaConfig.getAppKey().isBlank()) {
            throw new BadRequestException("QA assistant appKey 未配置");
        }
        if (qaConfig.getChatId() == null || qaConfig.getChatId().isBlank()) {
            throw new BadRequestException("QA assistant chatId 未配置");
        }
    }

    private String buildUserInput(ConnectionProfile profile, String question) {
        return """
            你现在只负责 MySQL 选表，不负责生成 SQL，也不要返回完整表结构。
            请忽略历史上下文，只根据本次输入完成任务。
            你必须只返回 JSON，不要返回 markdown，不要返回解释性前后缀。

            返回格式固定为：
            {
              "tables": ["表名1", "表名2"],
              "reason": "一句中文理由",
              "focus": ["字段1", "字段2"]
            }

            约束：
            1. tables 只返回最相关的 1 到 4 张表。
            2. tables 里的值只能是 MySQL 表名，不要返回字段说明句子。
            3. 如果问题已经明确指出某张表，优先只返回这张表。
            4. 必须优先依据知识库里已有的 MySQL 表结构文档和索引文档返回，不要编造不存在的表。
            5. reason 用一句中文概括原因。
            6. focus 返回建议重点关注的字段名，没有可返回空数组。

            前提条件：
            - 数据源类型：MySQL
            - 数据库名：%s
            - 数据库地址：%s:%d
            - 当前知识库里已经导入了该库的表结构文档和索引文档

            用户问题：
            %s
            """.formatted(
            profile.getDatabase(),
            profile.getHost(),
            profile.getPort(),
            question
        );
    }

    private String invokeChatAndCollect(String qaBaseUrl,
                                        Long appId,
                                        String appKey,
                                        String chatId,
                                        String userInput) {
        RestTemplate restTemplate = new RestTemplate();
        StringBuilder responseBuilder = new StringBuilder();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", appId);
        payload.put("appKey", appKey);
        payload.put("chatId", chatId);
        payload.put("userInput", userInput);
        try {
            restTemplate.execute(
                qaBaseUrl + "/consumer/api/chat",
                HttpMethod.POST,
                request -> {
                    request.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    request.getBody().write(JsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8));
                },
                response -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if (STREAM_END.equals(data)) {
                                break;
                            }
                            responseBuilder.append(data);
                        }
                    } catch (Exception exception) {
                        log.error("读取 QA assistant 选表 SSE 响应失败：qaBaseUrl={}, appId={}, chatId={}, message={}",
                            qaBaseUrl, appId, chatId, exception.getMessage(), exception);
                        throw new BadRequestException("读取 QA assistant 选表 SSE 响应失败: " + exception.getMessage());
                    }
                    return null;
                }
            );
        } catch (Exception exception) {
            log.error("调用 QA assistant 选表接口失败：qaBaseUrl={}, appId={}, chatId={}, message={}",
                qaBaseUrl, appId, chatId, exception.getMessage(), exception);
            throw new BadRequestException("调用 QA assistant 选表接口失败: " + exception.getMessage());
        }
        return responseBuilder.toString();
    }

    private MysqlTableSelectionResult parseSelectionResult(String rawResponse) {
        try {
            Map<String, Object> payload = JsonUtils.fromJson(extractJson(rawResponse), new TypeReference<>() {
            });
            List<String> tables = toStringList(payload.get("tables"));
            if (tables.isEmpty()) {
                throw new BadRequestException("QA assistant 未返回可用表名");
            }
            return MysqlTableSelectionResult.builder()
                .tables(tables)
                .reason(Objects.toString(payload.get("reason"), ""))
                .focus(toStringList(payload.get("focus")))
                .rawResponse(rawResponse)
                .build();
        } catch (Exception exception) {
            log.warn("解析 QA assistant 选表结果失败：rawResponse={}, message={}", rawResponse, exception.getMessage());
            throw new BadRequestException("QA assistant 选表结果解析失败");
        }
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(item -> Objects.toString(item, "").trim())
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private String extractJson(String rawResponse) {
        String trimmed = rawResponse == null ? "" : rawResponse.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BadRequestException("QA assistant 未返回 JSON 结果");
        }
        return trimmed.substring(start, end + 1);
    }

    private String normalizeBaseUrl(String qaBaseUrl) {
        String configured = qaBaseUrl;
        if (configured == null || configured.isBlank()) {
            configured = "http://43.242.200.67";
        }
        if (configured.endsWith("/")) {
            return configured.substring(0, configured.length() - 1);
        }
        return configured;
    }
}
