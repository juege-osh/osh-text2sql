package com.osh.text2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aicodee OpenAI 兼容接口配置。
 */
@Data
@ConfigurationProperties(prefix = "osh.text2sql.ai.aicodee")
public class AicodeeProperties {
    private String apiKey;
    private String baseUrl;
    private String model = "MiniMax-M2.7-highspeed";
    private String completionsPath = "/v1/chat/completions";
}
