package com.osh.text2sql.service;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.config.AicodeeProperties;
import com.osh.text2sql.config.OpenAiCompatibleProperties;
import com.osh.text2sql.config.OpenAiProxyProperties;
import com.osh.text2sql.config.Text2SqlProperties;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ssl.TLS;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiChatClientFactory {

    private final Text2SqlProperties properties;
    private final AicodeeProperties aicodeeProperties;
    private final OpenAiProxyProperties openAiProxyProperties;
    private final ChatClient dashscopeChatClient;
    private final ChatClient aicodeeChatClient;
    private final ChatClient openAiChatClient;

    public AiChatClientFactory(Text2SqlProperties properties,
                               AicodeeProperties aicodeeProperties,
                               OpenAiProxyProperties openAiProxyProperties,
                               ObjectProvider<ChatClient.Builder> builderProvider) {
        this.properties = properties;
        this.aicodeeProperties = aicodeeProperties;
        this.openAiProxyProperties = openAiProxyProperties;
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.dashscopeChatClient = builder == null ? null : builder.build();
        this.aicodeeChatClient = createCompatibleChatClient(AiProvider.AICODEE, compatiblePropertiesFor(AiProvider.AICODEE));
        this.openAiChatClient = createCompatibleChatClient(AiProvider.OPENAI, compatiblePropertiesFor(AiProvider.OPENAI));
    }

    public ChatClient currentClient() {
        return clientFor(currentProvider());
    }

    public ChatClient clientFor(AiProvider provider) {
        return switch (provider) {
            case AICODEE -> aicodeeChatClient;
            case DASHSCOPE -> dashscopeChatClient;
            case OPENAI -> openAiChatClient;
        };
    }

    public AiProvider currentProvider() {
        String configured = properties.getAi().getProvider();
        if (configured == null || configured.isBlank()) {
            return AiProvider.OPENAI;
        }
        return AiProvider.valueOf(configured.trim().toUpperCase());
    }

    public boolean isOpenAiConfigured() {
        return isCompatibleConfigured(compatiblePropertiesFor(AiProvider.OPENAI));
    }

    public boolean isAicodeeConfigured() {
        return isCompatibleConfigured(compatiblePropertiesFor(AiProvider.AICODEE));
    }

    public String currentReasoningEffort() {
        return properties.getAi().getReasoningEffort();
    }

    public String currentBaseUrl() {
        if (currentProvider() == AiProvider.DASHSCOPE) {
            return "(dashscope-autoconfig)";
        }
        return StrUtil.blankToDefault(compatiblePropertiesFor(currentProvider()).getBaseUrl(), "(empty)");
    }

    public String currentModel() {
        if (currentProvider() == AiProvider.DASHSCOPE) {
            return "(dashscope-config)";
        }
        return StrUtil.blankToDefault(compatiblePropertiesFor(currentProvider()).getModel(), "(empty)");
    }

    public String currentCompletionsPath() {
        if (currentProvider() == AiProvider.DASHSCOPE) {
            return "(dashscope-default)";
        }
        return StrUtil.blankToDefault(compatiblePropertiesFor(currentProvider()).getCompletionsPath(), "(empty)");
    }

    private boolean isCompatibleConfigured(OpenAiCompatibleProperties properties) {
        return StrUtil.isNotBlank(properties.getApiKey()) && StrUtil.isNotBlank(properties.getBaseUrl());
    }

    private OpenAiCompatibleProperties compatiblePropertiesFor(AiProvider provider) {
        return switch (provider) {
            case AICODEE -> new OpenAiCompatibleProperties(
                aicodeeProperties.getApiKey(),
                aicodeeProperties.getBaseUrl(),
                aicodeeProperties.getModel(),
                aicodeeProperties.getCompletionsPath()
            );
            case OPENAI -> new OpenAiCompatibleProperties(
                openAiProxyProperties.getApiKey(),
                openAiProxyProperties.getBaseUrl(),
                openAiProxyProperties.getModel(),
                openAiProxyProperties.getCompletionsPath()
            );
            case DASHSCOPE -> throw new IllegalArgumentException("DashScope 不使用 OpenAI 兼容配置");
        };
    }

    private ChatClient createCompatibleChatClient(AiProvider provider, OpenAiCompatibleProperties compatibleProperties) {
        if (!isCompatibleConfigured(compatibleProperties)) {
            return null;
        }
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
            .baseUrl(compatibleProperties.getBaseUrl())
            .apiKey(compatibleProperties.getApiKey())
            .completionsPath(compatibleProperties.getCompletionsPath());
        if (provider == AiProvider.AICODEE) {
            apiBuilder.restClientBuilder(buildTls12RestClientBuilder());
        }
        OpenAiApi openAiApi = apiBuilder.build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(compatibleProperties.getModel())
            .reasoningEffort(properties.getAi().getReasoningEffort())
            .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .build();
        return ChatClient.create(chatModel);
    }

    private RestClient.Builder buildTls12RestClientBuilder() {
        TlsSocketStrategy tlsSocketStrategy = (TlsSocketStrategy) ClientTlsStrategyBuilder.create()
            .setTlsVersions(TLS.V_1_2)
            .build();
        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsSocketStrategy)
                .build())
            .build();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
