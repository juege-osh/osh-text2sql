package com.osh.text2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 兼容接口配置。
 */
@Data
@ConfigurationProperties(prefix = "spring.openai")
public class OpenAiProxyProperties {
    private String apiKey;
    private String baseUrl;
    private String model = "gpt-5.4";
    private String completionsPath = "/chat/completions";
}
