package com.osh.text2sql.introspect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.util.JsonUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchIntrospector implements DatasourceIntrospector {

    @Override
    public DatasourceSchemaResponse introspect(ConnectionProfile profile) {
        RestTemplate restTemplate = createRestTemplate(profile);
        String indicesText = restTemplate.getForObject(profile.getBaseUrl() + "/_cat/indices?format=json", String.class);
        List<Map<String, Object>> indices = JsonUtils.fromJson(indicesText, new TypeReference<>() {
        });
        Map<String, Object> preview = new LinkedHashMap<>();
        indices.stream().limit(10).forEach(index -> {
            String name = String.valueOf(index.get("index"));
            String mapping = restTemplate.getForObject(profile.getBaseUrl() + "/" + name + "/_mapping", String.class);
            Map<String, Object> mappingMap = JsonUtils.fromJson(mapping, new TypeReference<>() {
            });
            preview.put(name, Map.of(
                "docsCount", index.get("docs.count"),
                "status", index.get("status"),
                "fields", extractFieldNames(mappingMap, name)
            ));
        });
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.ELASTICSEARCH)
            .name(profile.getBaseUrl())
            .summary("Elasticsearch 索引映射摘要，展示前 10 个索引")
            .schema(preview)
            .build();
    }

    @Override
    public ConnectionTestResponse test(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        RestTemplate restTemplate = createRestTemplate(profile);
        String result = restTemplate.getForObject(profile.getBaseUrl() + "/", String.class);
        return ConnectionTestResponse.builder()
            .success(true)
            .message("Elasticsearch 连接成功")
            .elapsedMs(Duration.ofMillis(System.currentTimeMillis() - start).toMillis())
            .preview(JsonUtils.fromJson(result, new TypeReference<Map<String, Object>>() {
            }))
            .build();
    }

    public RestTemplate createRestTemplate(ConnectionProfile profile) {
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        if (profile.getUsername() != null && !profile.getUsername().isBlank()) {
            credentialsProvider.setCredentials(new AuthScope(null, -1),
                new UsernamePasswordCredentials(profile.getUsername(), profile.getPassword() == null ? new char[0] : profile.getPassword().toCharArray()));
        }
        CloseableHttpClient httpClient = HttpClients.custom()
            .setDefaultCredentialsProvider(credentialsProvider)
            .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return execution.execute(request, body);
        });
        return restTemplate;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractFieldNames(Map<String, Object> mappingMap, String indexName) {
        Object root = mappingMap.get(indexName);
        if (!(root instanceof Map<?, ?> rootMap)) {
            return List.of();
        }
        Object mappings = ((Map<String, Object>) rootMap).get("mappings");
        if (!(mappings instanceof Map<?, ?> mappingsMap)) {
            return List.of();
        }
        Object properties = ((Map<String, Object>) mappingsMap).get("properties");
        if (!(properties instanceof Map<?, ?> propertiesMap)) {
            return List.of();
        }
        return propertiesMap.keySet().stream()
            .map(String::valueOf)
            .sorted()
            .limit(30)
            .toList();
    }
}
