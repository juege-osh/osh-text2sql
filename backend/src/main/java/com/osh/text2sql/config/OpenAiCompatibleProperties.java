package com.osh.text2sql.config;

import lombok.Getter;

/**
 * OpenAI 兼容客户端配置抽象。
 */
@Getter
public class OpenAiCompatibleProperties {

    private static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String completionsPath;

    public OpenAiCompatibleProperties(String apiKey, String baseUrl, String model, String completionsPath) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.completionsPath = normalizeCompletionsPath(completionsPath);
    }

    private String normalizeCompletionsPath(String completionsPath) {
        if (completionsPath == null || completionsPath.isBlank()) {
            return DEFAULT_COMPLETIONS_PATH;
        }
        return completionsPath;
    }
}
